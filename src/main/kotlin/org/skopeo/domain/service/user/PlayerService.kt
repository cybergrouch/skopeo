// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.user
import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import org.skopeo.common.dto.event.MyEventResponse
import org.skopeo.common.dto.rating.RatingHistoryResponse
import org.skopeo.common.dto.user.ActivePointsAwardResponse
import org.skopeo.common.dto.user.MatchHistoryParticipant
import org.skopeo.common.dto.user.OpponentBand
import org.skopeo.common.dto.user.OpponentBandSeries
import org.skopeo.common.dto.user.OpponentSummary
import org.skopeo.common.dto.user.PlayerMatchHistoryEntry
import org.skopeo.common.dto.user.PlayerMatchHistoryPage
import org.skopeo.common.dto.user.PlayerResultsSummary
import org.skopeo.common.dto.user.PlayerStandingResponse
import org.skopeo.common.dto.user.PublicPlayerResponse
import org.skopeo.common.dto.user.PublicRatingDto
import org.skopeo.common.dto.user.ResultsBucket
import org.skopeo.common.dto.user.ResultsTotals
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.dto.event.toResponse
import org.skopeo.domain.mapper.dto.rating.toResponse
import org.skopeo.domain.mapper.entity.event.toDomain
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.ranking.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.ContactType
import org.skopeo.domain.model.Match
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.canSeeRawRatingOrFalse
import org.skopeo.domain.service.rating.RatingAssembler
import org.skopeo.domain.service.standings.StandingsService
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.UserRepository
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.UUID

// Match-history pagination (#284): the default page size and the hard cap, mirroring player search (#232).
private const val DEFAULT_HISTORY_LIMIT = 20
private const val MAX_HISTORY_LIMIT = 100

// #630: capabilities that, like the owner, may see a player's registered email on the public profile.
private val EMAIL_VIEW_ROLES = setOf(Capability.HOST, Capability.CLUB_OWNER, Capability.RATER, Capability.ADMINISTRATOR)

/**
 * Resolves a player's shareable, auth-gated public profile from their [public code] (issue #61).
 * Open to any authenticated user (the route is behind auth); returns only a privacy-conscious
 * subset, not the full account.
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
@Suppress("TooManyFunctions") // Cohesive public-read surface (profile/history/results/standing/points) + #622 gate.
class PlayerService(
    private val users: UserRepository = UserRepository(),
    private val ratings: RatingAssembler = RatingAssembler(),
    private val matches: MatchRepository = MatchRepository(),
    private val standings: StandingsService = StandingsService(),
    private val awards: RankingPointRepository = RankingPointRepository(),
    private val events: EventRepository = EventRepository(),
) {
    fun publicProfile(
        code: String,
        // Optional viewer token (#193/#583/#630): the raw NTRP value and the registered email are revealed
        // only to privileged viewers — anonymous callers see neither. See [showRaw]/[canSeeEmail] below.
        token: VerifiedFirebaseToken? = null,
    ): Either<ServiceError, PublicPlayerResponse> =
        either {
            val located = resolve(code = code, requireActive = false).bind()
            if (!located.isActive) {
                mergedCard(located = located).bind()
            } else {
                val rating = ratings.findCurrentRating(userId = located.id)
                val viewer = token?.let { users.findByFirebaseUid(firebaseUid = it.uid)?.toDomain() }
                val showRaw = viewer.canSeeRawRatingOrFalse()
                // #630: reveal the email to the profile owner, or a HOST/CLUB_OWNER/RATER/ADMINISTRATOR
                // viewer — gated here so it never leaves the API to another plain PLAYER or an anonymous caller.
                val canSeeEmail =
                    viewer != null && (viewer.id == located.id || viewer.capabilities.any { it in EMAIL_VIEW_ROLES })
                PublicPlayerResponse(
                    publicCode = located.publicCode,
                    displayName = located.displayName(),
                    photoUrl = located.photoUrl,
                    email = if (canSeeEmail) primaryEmailOf(user = located) else null,
                    rating =
                        rating?.let {
                            PublicRatingDto(
                                value = if (showRaw) it.currentRating.toPlainString() else null,
                                level = it.currentLevel,
                                confidence = it.confidence.toPlainString(),
                            )
                        },
                    // A login-less, unclaimed placeholder renders an "unclaimed" indicator + claim entry (#496).
                    isPlaceholder = located.placeholder,
                    // #622: surfaced so the owner's own profile view can warn that history is hidden from others.
                    matchHistoryHidden = located.matchHistoryHidden,
                )
            }
        }

    /**
     * The player's registered email to display (#630): their active, primary EMAIL contact, falling back
     * to any active EMAIL when none is flagged primary. Null when the player has no email on file. The
     * caller decides whether the viewer is allowed to see it — this only picks which value that would be.
     */
    private fun primaryEmailOf(user: User): String? =
        user.contacts
            .filter { it.isActive && it.type == ContactType.EMAIL }
            .let { active -> active.firstOrNull { it.isPrimary } ?: active.firstOrNull() }
            ?.value?.revealed

    /**
     * A disabled duplicate (#124) renders a "merged" card linking to its canonical account; a
     * plain-deactivated (admin-deleted, #518) account has no canonical and stays hidden from direct
     * browsing (treated as not-found). Its historical references elsewhere still carry the "Deleted"
     * flag; only the direct profile-by-code lookup is a 404, matching the existing contract.
     */
    private fun mergedCard(located: User): Either<ServiceError, PublicPlayerResponse> {
        val canonicalId = located.canonicalUserId
        val canonical = if (canonicalId == null) null else users.findById(id = canonicalId).getOrNull()?.toDomain()
        if (canonical == null) {
            return ServiceError.NotFound(message = "No player with code ${located.publicCode}").left()
        }
        return PublicPlayerResponse(
            publicCode = located.publicCode,
            displayName = located.displayName(),
            photoUrl = located.photoUrl,
            rating = null,
            isDisabled = true,
            canonical =
                OpponentSummary(
                    publicCode = canonical.publicCode,
                    displayName = canonical.displayName(),
                    photoUrl = canonical.photoUrl,
                ),
        ).right()
    }

    /**
     * A page of the player's match history (#65, #284), newest first, optionally narrowed by [search] (a
     * case-insensitive substring over opponent/partner display names and public codes). Returns the
     * requested [offset]/[limit] slice plus the total matching count, so the profile can show a bounded
     * preview and a full page can paginate. The full oriented history is assembled server-side (as
     * before) then filtered and sliced; the client no longer loads every row.
     */
    fun matchHistory(
        code: String,
        limit: Int = DEFAULT_HISTORY_LIMIT,
        offset: Int = 0,
        search: String? = null,
        opponentBand: String? = null,
        // #622: the optional viewer decides whether a hidden history is revealed (owner / elevated role).
        token: VerifiedFirebaseToken? = null,
    ): Either<ServiceError, PlayerMatchHistoryPage> =
        either {
            val user = resolve(code = code).bind()
            // #622: unprivileged viewers of a hidden history get an empty, `hidden`-flagged page (enforced
            // here so the API never leaks the rows); the owner and elevated roles fall through to the real one.
            hiddenMatchHistoryPageOrNull(target = user, token = token)?.let { return@either it }
            // A canonical account's history also surfaces its disabled duplicates' matches (#124,
            // display-only — ratings are never consolidated). Each match is oriented from whichever of
            // these "self" ids actually played it.
            val selfIds = (listOf(element = user.id) + users.findDuplicatesOf(canonicalId = user.id).map { it.toDomain().id }).toSet()
            val played =
                selfIds
                    .flatMap { matches.listByUser(userId = it) }
                    .map { it.toDomain() }
                    .distinctBy { it.id }
                    .sortedByDescending { it.matchDate }
            val ratedMatchIds = played.filter { it.ratedAt != null }.map { it.id }
            // Raw at-the-time NTRP is a raw-NTRP reveal (#583/#654): only an admin not previewing as a
            // non-admin sees it; everyone else gets the band ([levelAtMatch]) only.
            val showRaw = token?.let { users.findByFirebaseUid(firebaseUid = it.uid)?.toDomain() }.canSeeRawRatingOrFalse()
            val atMatchByMatch = atMatchRatings(ratedMatchIds = ratedMatchIds, showRaw = showRaw)
            val participantByMatch = played.associate { it.id to participantOf(match = it, selfIds = selfIds) }
            // Resolve every other participant (partners + opponents) across all matches in one lookup.
            val otherIds =
                played.flatMap { match ->
                    val self = participantByMatch.getValue(key = match.id)
                    (match.team1.userIds + match.team2.userIds).filterNot { it == self }
                }
            val participantsById = users.findAllByIds(ids = otherIds).map { it.toDomain() }.associateBy { it.id }
            // Every participant's *current* rating confidence (#343), shown beside the at-the-time band.
            val confidenceById =
                ratings
                    .findCurrentRatings(userIds = (selfIds + otherIds).toList())
                    .mapValues { (_, rating) -> rating.confidence.toPlainString() }
            val entries =
                played.map { match ->
                    val participant = participantByMatch.getValue(key = match.id)
                    entry(
                        match = match,
                        playerId = participant,
                        players = participantsById,
                        atMatch = atMatchByMatch[match.id],
                        confidences = confidenceById,
                    )
                }

            // Case-insensitive match on any opponent/partner display name or public code.
            fun rowMatches(
                row: PlayerMatchHistoryEntry,
                needle: String,
            ): Boolean =
                (row.opponents + row.partners).any { p ->
                    p.publicCode.lowercase().contains(other = needle) || p.displayName?.lowercase()?.contains(other = needle) == true
                }

            // A blank/absent search returns everything; otherwise filter on the normalized needle. Coalesce
            // to "" up front with orEmpty() (single covered branch) rather than chaining `?.trim()?.lowercase()`,
            // whose second safe-call is an unreachable arm — trim() never returns null — that no test can cover.
            val needle = search.orEmpty().trim().lowercase()
            val matched =
                if (needle.isEmpty()) {
                    entries
                } else {
                    entries.filter { rowMatches(row = it, needle = needle) }
                }
            // Opponent-band filter (#563): keep rows where an opponent's at-the-time band equals [opponentBand].
            // Applied before pagination so it is correct across pages. Blank/absent → no filter.
            val band = opponentBand.orEmpty().trim()
            val filtered =
                if (band.isEmpty()) {
                    matched
                } else {
                    matched.filter { row -> row.opponents.any { it.levelAtMatch == band } }
                }
            PlayerMatchHistoryPage(
                items =
                    filtered
                        .drop(n = offset.coerceAtLeast(minimumValue = 0))
                        .take(n = limit.coerceIn(minimumValue = 1, maximumValue = MAX_HISTORY_LIMIT)),
                total = filtered.size,
            )
        }

    // #622: for a hidden history, the empty `hidden`-flagged page unless the viewer is the owner or holds
    // an elevated capability (anything beyond plain PLAYER); an anonymous (null) viewer never qualifies.
    // Returns null when the history is visible to this viewer, so the caller falls through to the real page.
    private fun hiddenMatchHistoryPageOrNull(
        target: User,
        token: VerifiedFirebaseToken?,
    ): PlayerMatchHistoryPage? {
        if (!target.matchHistoryHidden) return null
        val viewer = token?.let { users.findByFirebaseUid(firebaseUid = it.uid)?.toDomain() }
        val privileged = viewer != null && (viewer.id == target.id || viewer.capabilities.any { it != Capability.PLAYER })
        return if (privileged) null else PlayerMatchHistoryPage(items = emptyList(), total = 0, hidden = true)
    }

    /**
     * A player's win–loss record over time (#276): every decided match (with a recorded winner) they
     * played — a canonical account also including its duplicates' matches (#124) — bucketed by calendar
     * month and split into singles vs doubles (MIXED_DOUBLES counts as doubles). Aggregated server-side
     * so the chart is independent of how match history is listed/paginated. Only band-agnostic W/L
     * counts leave the service — never a rating.
     */
    fun resultsSummary(code: String): Either<ServiceError, PlayerResultsSummary> =
        either {
            val user = resolve(code = code).bind()
            val selfIds = (listOf(element = user.id) + users.findDuplicatesOf(canonicalId = user.id).map { it.toDomain().id }).toSet()
            val decided =
                selfIds
                    .flatMap { matches.listByUser(userId = it) }
                    .map { it.toDomain() }
                    .distinctBy { it.id }
                    .filter { it.winnerTeamId != null }

            val rows =
                decided.map { match ->
                    val self = participantOf(match = match, selfIds = selfIds)
                    val selfTeamId = if (self in match.team1.userIds) match.team1.teamId else match.team2.teamId
                    ResultRow(
                        singles = match.matchFormat == TeamType.SINGLES,
                        period = match.matchDate.toString().take(n = 7),
                        won = match.winnerTeamId == selfTeamId,
                    )
                }
            val singles = totalsOf(rows = rows.filter { it.singles })
            val doubles = totalsOf(rows = rows.filterNot { it.singles })

            val series = opponentBandSeries(decided = decided, selfIds = selfIds)

            PlayerResultsSummary(
                singles = singles,
                doubles = doubles,
                overall = totalsOf(rows = rows),
                opponentBands = series,
                monthsWindow = RESULTS_MONTHS_WINDOW,
                monthlyMax = series.flatMap { it.monthly }.maxOfOrNull { it.wins + it.losses } ?: 0,
            )
        }

    /**
     * A player's event-participation history by public code (#704) for the public profile's "Events
     * history" card — parity with the owner dashboard, which reads the caller's own events via
     * `/events/mine`. Every event the player is on, in any status, with their standing ([status]) and
     * the batched "has results" [completedMatchCount] the client buckets on — the same DTO shape the
     * `/events/mine` endpoint returns, so the card renders identically. Events are already publicly
     * shareable (`/events/{code}` lists their rosters), so this is not further gated; it only resolves
     * the target player by code instead of the authenticated caller.
     */
    fun eventHistory(code: String): Either<ServiceError, List<MyEventResponse>> =
        either {
            val user = resolve(code = code).bind()
            val mine = events.findForParticipant(userId = user.id).map { it.toDomain() }
            // Batched "has results" counts (#483), matching EventService.myEvents so buckets align.
            val counts = matches.completedResultCountByEvents(eventIds = mine.map { it.event.id })
            mine.map { it.toResponse(completedMatchCount = counts[it.event.id] ?: 0) }
        }

    /**
     * A player's full rating history by code, for ADMINISTRATORs only (issue #73). The owner reads
     * their own history via the user-id endpoint; this code-based variant exists so an admin viewing
     * a public profile can see it. Unlike match history, this is the precise audit view.
     */
    fun ratingHistory(
        token: VerifiedFirebaseToken,
        code: String,
    ): Either<ServiceError, List<RatingHistoryResponse>> =
        either {
            // Raw rating history is a raw-NTRP reveal (#583/#654): ADMINISTRATOR only, AND not while that
            // admin previews as a non-admin (the per-admin toggle). A previewing admin is treated exactly
            // like a non-admin here — Forbidden — so the preview faithfully hides raw ratings. The owner
            // reads their own history via the user-id endpoint (#73).
            val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
            ensure(condition = caller.canSeeRawRatingOrFalse()) { ServiceError.Forbidden() }
            val user = resolve(code = code).bind()
            // Everyone who passes the gate above is a raw-rating viewer, so reveal the raw NTRP values —
            // otherwise toResponse() defaults to nulling them and the admin sees bands only (#654).
            ratings.historyByUser(userId = user.id).map { it.toResponse(revealRawValue = true) }
        }

    /**
     * A player's current competitive standing (#448) by code — their rank within their (band, sex)
     * group and the source-appropriate metric (#457), under the active `standings_source`. Rank + band
     * are public (#64/#114), so this needs no token and renders on the anonymous public profile; the
     * route is `authenticate(optional = true)` and threads the optional caller for the reveal check.
     *
     * Privacy (#186): under POINTS the points total is public (shown to every viewer). Under RATING the
     * precise rating is revealed only to a RATER/ADMINISTRATOR or the owner viewing their own profile —
     * omitted for anonymous / other viewers, who then see rank + band only (no numeric value leaked). A
     * right-null means the player is unranked (unrated / no points in the current standings).
     */
    fun standing(
        token: VerifiedFirebaseToken?,
        code: String,
    ): Either<ServiceError, PlayerStandingResponse?> =
        either {
            val user = resolve(code = code).bind()
            standings.locatePlayer(userId = user.id)?.let { standing ->
                // Reveal the precise rating only to an ADMINISTRATOR (#583) — no longer RATER/owner. The
                // per-admin "preview as non-admin" toggle further suppresses it. Everyone else: rank + band.
                val caller = token?.let { users.findByFirebaseUid(firebaseUid = it.uid)?.toDomain() }
                val canSeeRating = caller.canSeeRawRatingOrFalse()
                PlayerStandingResponse(
                    band = standing.band.code,
                    bandLabel = standing.band.label,
                    sex = standing.sex,
                    rank = standing.rank,
                    source = standing.source.name,
                    // POINTS metric is public; RATING metric is reveal-gated.
                    points = standing.points?.toPlainString(),
                    rating = if (canSeeRating) standing.rating?.toPlainString() else null,
                )
            }
        }

    /**
     * A player's ACTIVE ranking-point awards (#448) for the profile points audit — owner-or-admin only.
     * Each award carries its points, band, expiry ([validUntil]), and a link to the granting match (its
     * public code → `/matches/:code`); an award with no match link (a manual grant or a pre-V19 finalize
     * award) falls back to the event code (→ `/events/:code`). Not visible to other / anonymous viewers.
     */
    fun activePoints(
        token: VerifiedFirebaseToken,
        code: String,
    ): Either<ServiceError, List<ActivePointsAwardResponse>> =
        either {
            val user = resolve(code = code).bind()
            // Owner-or-admin only (#448): the caller is the profile owner, or holds ADMINISTRATOR.
            val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
            val allowed = caller != null && (caller.id == user.id || Capability.ADMINISTRATOR in caller.capabilities)
            ensure(condition = allowed) { ServiceError.Forbidden() }

            val active = awards.listActiveByUser(userId = user.id, asOf = LocalDateTime.now()).map { it.toDomain() }
            val matchCodes = matches.publicRefsByIds(ids = active.mapNotNull { it.matchId }).mapValues { it.value.publicCode }
            val eventCodes = events.publicCodesByIds(ids = active.mapNotNull { it.eventId })
            // Prefer the match link; fall back to the event only when there is no match (manual / pre-V19).
            active.map { award ->
                val matchCode = award.matchId?.let { matchCodes[it] }
                ActivePointsAwardResponse(
                    id = award.id.toString(),
                    points = award.points.toPlainString(),
                    band = award.band,
                    pointClass = award.pointClass.name,
                    validUntil = award.validUntil.toString(),
                    matchCode = matchCode,
                    eventCode = if (matchCode == null) award.eventId?.let { eventCodes[it] } else null,
                )
            }
        }

    /**
     * Resolve a player by public code (case-insensitive); 404 when unknown. [requireActive] = true (the
     * default) also 404s a deactivated account for the normal reads; the public-profile path passes false
     * so a disabled duplicate still resolves to a viewable "merged" card (#124).
     */
    private fun resolve(
        code: String,
        requireActive: Boolean = true,
    ): Either<ServiceError, User> {
        val normalized = code.trim().uppercase()
        val user = users.findByPublicCode(code = normalized)?.toDomain()
        return if (user == null || (requireActive && !user.isActive)) {
            ServiceError.NotFound(message = "No player with code $normalized").left()
        } else {
            user.right()
        }
    }

    /** The "self" id (canonical or one of its duplicates) that actually played [match]. */
    private fun participantOf(
        match: Match,
        selfIds: Set<UUID>,
    ): UUID = (match.team1.userIds + match.team2.userIds).first { it in selfIds }

    private fun entry(
        match: Match,
        playerId: UUID,
        players: Map<UUID, User>,
        atMatch: AtMatchRatings?,
        confidences: Map<UUID, String>,
    ): PlayerMatchHistoryEntry {
        val onTeam1 = playerId in match.team1.userIds
        val playerTeam = if (onTeam1) match.team1 else match.team2
        val opposingTeam = if (onTeam1) match.team2 else match.team1

        fun participant(userId: UUID): MatchHistoryParticipant {
            val user = players.getValue(key = userId)
            return MatchHistoryParticipant(
                publicCode = user.publicCode,
                displayName = user.displayName(),
                photoUrl = user.photoUrl,
                levelAtMatch = atMatch?.levels?.get(key = userId),
                ratingAtMatch = atMatch?.raw?.get(key = userId),
                confidence = confidences[userId],
                isPlaceholder = user.placeholder,
                isDeleted = user.isDeleted(),
            )
        }

        return PlayerMatchHistoryEntry(
            matchId = match.id.toString(),
            publicCode = match.publicCode,
            matchDate = match.matchDate.toString(),
            status = match.status.name,
            rated = match.ratedAt != null,
            result = match.winnerTeamId?.let { if (it == playerTeam.teamId) "WIN" else "LOSS" },
            setScores =
                match.sets.map { set ->
                    val playerGames = if (onTeam1) set.team1Games else set.team2Games
                    val opponentGames = if (onTeam1) set.team2Games else set.team1Games
                    "$playerGames-$opponentGames"
                },
            partners = playerTeam.userIds.filterNot { it == playerId }.map(transform = ::participant),
            opponents = opposingTeam.userIds.map(transform = ::participant),
            playerLevelAtMatch = atMatch?.levels?.get(key = playerId),
            playerRatingAtMatch = atMatch?.raw?.get(key = playerId),
            playerConfidence = confidences[playerId],
        )
    }

    /**
     * The three band-relation series behind the profile's donut and sparklines (#845).
     *
     * Restricted to **rated singles** deliberately: classifying a matchup needs both sides' band *as at
     * the match*, which only exists once a match is rated, and a doubles result says too little about one
     * player's level to classify at all. Its counts therefore do not reconcile with the singles totals —
     * the UI states both limits rather than implying a discrepancy.
     */
    private fun opponentBandSeries(
        decided: List<Match>,
        selfIds: Set<UUID>,
    ): List<OpponentBandSeries> {
        // The band split needs BOTH sides' band as at the match, which only exists once a match has
        // been rated (#845) — hence singles + rated only, stated in the UI rather than reconciled.
        val ratedSingles = decided.filter { it.matchFormat == TeamType.SINGLES && it.ratedAt != null }
        val bandsByMatch = atMatchRatings(ratedMatchIds = ratedSingles.map { it.id }, showRaw = false)
        val classified =
            ratedSingles.mapNotNull { match ->
                val self = participantOf(match = match, selfIds = selfIds)
                val opponent = (match.team1.userIds + match.team2.userIds).firstOrNull { it != self } ?: return@mapNotNull null
                val levels = bandsByMatch[match.id]?.levels ?: return@mapNotNull null
                // Either side missing a band cannot be classified; it falls outside this cut rather
                // than into a catch-all bucket, which is what the rated-only caption already covers.
                val selfBand = levels[self]?.toBigDecimalOrNull() ?: return@mapNotNull null
                val opponentBand = levels[opponent]?.toBigDecimalOrNull() ?: return@mapNotNull null
                val selfTeamId = if (self in match.team1.userIds) match.team1.teamId else match.team2.teamId
                BandRow(
                    relation =
                        when {
                            opponentBand.compareTo(other = selfBand) == 0 -> OpponentBand.SAME
                            opponentBand > selfBand -> OpponentBand.HIGHER
                            else -> OpponentBand.LOWER
                        },
                    period = match.matchDate.toString().take(n = 7),
                    won = match.winnerTeamId == selfTeamId,
                )
            }

        // A fixed trailing window of months, oldest first, every month present (#845): an absence must
        // render as a gap, so the client is handed zeroes rather than left to infer missing periods.
        val months = (0 until RESULTS_MONTHS_WINDOW).map { YearMonth.now().minusMonths(it.toLong()).toString() }.reversed()
        val series =
            OpponentBand.entries.map { relation ->
                val forRelation = classified.filter { it.relation == relation }
                OpponentBandSeries(
                    relation = relation,
                    totals = totalsOf(rows = forRelation.map { ResultRow(singles = true, period = it.period, won = it.won) }),
                    monthly =
                        months.map { period ->
                            val inMonth = forRelation.filter { it.period == period }
                            ResultsBucket(period = period, wins = inMonth.count { it.won }, losses = inMonth.count { !it.won })
                        },
                )
            }

        return OpponentBand.entries.map { relation ->
            val forRelation = classified.filter { it.relation == relation }
            OpponentBandSeries(
                relation = relation,
                totals = totalsOf(rows = forRelation.map { ResultRow(singles = true, period = it.period, won = it.won) }),
                monthly =
                    months.map { period ->
                        val inMonth = forRelation.filter { it.period == period }
                        ResultsBucket(period = period, wins = inMonth.count { it.won }, losses = inMonth.count { !it.won })
                    },
            )
        }
    }

    /**
     * Per-match at-the-time rating lookups by user id (#654): the published [levels] band and — only when
     * the viewer may see raw ratings ([showRaw]) — the [raw] NTRP value. Both come from each match's
     * live rating-history rows; [raw] is null for a non-raw viewer so band-only leaves the API.
     */
    private fun atMatchRatings(
        ratedMatchIds: List<UUID>,
        showRaw: Boolean,
    ): Map<UUID?, AtMatchRatings> =
        ratings.historyForMatches(matchIds = ratedMatchIds).groupBy { it.matchId }.mapValues { (_, rows) ->
            AtMatchRatings(
                levels = rows.associate { it.userId to it.previousLevel },
                raw = if (showRaw) rows.associate { it.userId to it.previousRating.toPlainString() } else null,
            )
        }
}

/** At-the-time NTRP for one match keyed by user id (#654): the band [levels] and the raw [raw] (raw-viewers only). */
private class AtMatchRatings(
    val levels: Map<UUID, String?>,
    val raw: Map<UUID, String>?,
)

/** The trailing window the results-summary sparklines cover (#845). */
private const val RESULTS_MONTHS_WINDOW = 12

/**
 * Finished totals for a set of rows (#845). The win rate is **null** when nothing is decided rather than
 * 0 — "n/a" and "0%" are different claims, and deciding that here keeps the branch out of the client.
 */
private fun totalsOf(rows: List<ResultRow>): ResultsTotals {
    val wins = rows.count { it.won }
    val losses = rows.size - wins
    return ResultsTotals(
        played = rows.size,
        wins = wins,
        losses = losses,
        winRate = if (rows.isEmpty()) null else Math.round(wins * PERCENT / rows.size.toDouble()).toInt(),
    )
}

private const val PERCENT = 100.0

/** One rated singles match reduced to its band relation, month and outcome (#845). */
private data class BandRow(
    val relation: OpponentBand,
    val period: String,
    val won: Boolean,
)

/** One decided match reduced to what the results summary needs (#276): format, month, and outcome. */
private data class ResultRow(
    val singles: Boolean,
    val period: String,
    val won: Boolean,
)
