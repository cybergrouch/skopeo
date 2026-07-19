// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import org.skopeo.dto.user.ActivePointsAwardResponse
import org.skopeo.dto.user.MatchHistoryParticipant
import org.skopeo.dto.user.OpponentSummary
import org.skopeo.dto.user.PlayerMatchHistoryEntry
import org.skopeo.dto.user.PlayerMatchHistoryPage
import org.skopeo.dto.user.PlayerResultsSummary
import org.skopeo.dto.user.PlayerStandingResponse
import org.skopeo.dto.user.PublicPlayerResponse
import org.skopeo.dto.user.PublicRatingDto
import org.skopeo.dto.user.ResultsBucket
import org.skopeo.model.Capability
import org.skopeo.model.Match
import org.skopeo.model.RatingHistoryEntry
import org.skopeo.model.ServiceError
import org.skopeo.model.TeamType
import org.skopeo.model.User
import org.skopeo.model.displayName
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.standings.StandingsService
import java.time.LocalDateTime
import java.util.UUID

// Match-history pagination (#284): the default page size and the hard cap, mirroring player search (#232).
private const val DEFAULT_HISTORY_LIMIT = 20
private const val MAX_HISTORY_LIMIT = 100

/**
 * Resolves a player's shareable, auth-gated public profile from their [public code] (issue #61).
 * Open to any authenticated user (the route is behind auth); returns only a privacy-conscious
 * subset, not the full account.
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
class PlayerService(
    private val users: UserRepository = UserRepository(),
    private val ratings: RatingRepository = RatingRepository(),
    private val matches: MatchRepository = MatchRepository(),
    private val standings: StandingsService = StandingsService(),
    private val awards: RankingPointRepository = RankingPointRepository(),
    private val events: EventRepository = EventRepository(),
) {
    fun publicProfile(code: String): Either<ServiceError, PublicPlayerResponse> =
        either {
            val located = resolve(code = code, requireActive = false).bind()
            if (!located.isActive) {
                mergedCard(located = located).bind()
            } else {
                val rating = ratings.findCurrentRating(userId = located.id)
                PublicPlayerResponse(
                    publicCode = located.publicCode,
                    displayName = located.displayName(),
                    photoUrl = located.photoUrl,
                    rating =
                        rating?.let {
                            PublicRatingDto(
                                value = it.currentRating.toPlainString(),
                                level = it.currentLevel,
                                confidence = it.confidence.toPlainString(),
                            )
                        },
                    // A login-less, unclaimed placeholder renders an "unclaimed" indicator + claim entry (#496).
                    isPlaceholder = located.placeholder,
                )
            }
        }

    /**
     * A disabled duplicate (#124) renders a "merged" card linking to its canonical account; a
     * plain-deactivated account (no canonical) stays hidden (treated as not-found).
     */
    private fun mergedCard(located: User): Either<ServiceError, PublicPlayerResponse> {
        val canonicalId = located.canonicalUserId
        val canonical = if (canonicalId == null) null else users.findById(id = canonicalId).getOrNull()
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
    ): Either<ServiceError, PlayerMatchHistoryPage> =
        either {
            val user = resolve(code = code).bind()
            // A canonical account's history also surfaces its disabled duplicates' matches (#124,
            // display-only — ratings are never consolidated). Each match is oriented from whichever of
            // these "self" ids actually played it.
            val selfIds = (listOf(element = user.id) + users.findDuplicatesOf(canonicalId = user.id).map { it.id }).toSet()
            val played =
                selfIds
                    .flatMap { matches.listByUser(userId = it) }
                    .distinctBy { it.id }
                    .sortedByDescending { it.matchDate }
            val ratedMatchIds = played.filter { it.ratedAt != null }.map { it.id }
            val levelByMatchAndUser =
                ratings
                    .historyForMatches(matchIds = ratedMatchIds)
                    .groupBy { it.matchId }
                    .mapValues { (_, rows) -> rows.associate { it.userId to it.previousLevel } }
            val participantByMatch = played.associate { it.id to participantOf(match = it, selfIds = selfIds) }
            // Resolve every other participant (partners + opponents) across all matches in one lookup.
            val otherIds =
                played.flatMap { match ->
                    val self = participantByMatch.getValue(key = match.id)
                    (match.team1.userIds + match.team2.userIds).filterNot { it == self }
                }
            val participantsById = users.findAllByIds(ids = otherIds).associateBy { it.id }
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
                        levels = levelByMatchAndUser[match.id],
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
            PlayerMatchHistoryPage(
                items =
                    matched
                        .drop(n = offset.coerceAtLeast(minimumValue = 0))
                        .take(n = limit.coerceIn(minimumValue = 1, maximumValue = MAX_HISTORY_LIMIT)),
                total = matched.size,
            )
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
            val selfIds = (listOf(element = user.id) + users.findDuplicatesOf(canonicalId = user.id).map { it.id }).toSet()
            val rows =
                selfIds
                    .flatMap { matches.listByUser(userId = it) }
                    .distinctBy { it.id }
                    .filter { it.winnerTeamId != null }
                    .map { match ->
                        val self = participantOf(match = match, selfIds = selfIds)
                        val selfTeamId = if (self in match.team1.userIds) match.team1.teamId else match.team2.teamId
                        ResultRow(
                            singles = match.matchFormat == TeamType.SINGLES,
                            period = match.matchDate.toString().take(n = 7),
                            won = match.winnerTeamId == selfTeamId,
                        )
                    }

            // One ResultsBucket per month (oldest first) for a given format's rows.
            fun bucketsOf(formatRows: List<ResultRow>): List<ResultsBucket> =
                formatRows
                    .groupBy { it.period }
                    .toSortedMap()
                    .map { (period, monthRows) ->
                        ResultsBucket(period = period, wins = monthRows.count { it.won }, losses = monthRows.count { !it.won })
                    }

            PlayerResultsSummary(
                singles = bucketsOf(formatRows = rows.filter { it.singles }),
                doubles = bucketsOf(formatRows = rows.filterNot { it.singles }),
            )
        }

    /**
     * A player's full rating history by code, for ADMINISTRATORs only (issue #73). The owner reads
     * their own history via the user-id endpoint; this code-based variant exists so an admin viewing
     * a public profile can see it. Unlike match history, this is the precise audit view.
     */
    fun ratingHistory(
        token: VerifiedFirebaseToken,
        code: String,
    ): Either<ServiceError, List<RatingHistoryEntry>> =
        either {
            // ADMINISTRATOR-only: the owner reads their own history via the user-id endpoint (#73).
            val caller = users.findByFirebaseUid(firebaseUid = token.uid)
            ensure(condition = caller != null && Capability.ADMINISTRATOR in caller.capabilities) { ServiceError.Forbidden() }
            val user = resolve(code = code).bind()
            ratings.historyByUser(userId = user.id)
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
                // Reveal the precise rating only to a rate-privileged caller or the profile owner (#186).
                val caller = token?.let { users.findByFirebaseUid(firebaseUid = it.uid) }
                val canSeeRating =
                    caller != null &&
                        (
                            caller.id == user.id ||
                                caller.capabilities.any { it == Capability.RATER || it == Capability.ADMINISTRATOR }
                        )
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
            val caller = users.findByFirebaseUid(firebaseUid = token.uid)
            val allowed = caller != null && (caller.id == user.id || Capability.ADMINISTRATOR in caller.capabilities)
            ensure(condition = allowed) { ServiceError.Forbidden() }

            val active = awards.listActiveByUser(userId = user.id, asOf = LocalDateTime.now())
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
        val user = users.findByPublicCode(code = normalized)
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
        levels: Map<UUID, String?>?,
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
                levelAtMatch = levels?.get(key = userId),
                confidence = confidences[userId],
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
            playerLevelAtMatch = levels?.get(key = playerId),
            playerConfidence = confidences[playerId],
        )
    }
}

/** One decided match reduced to what the results summary needs (#276): format, month, and outcome. */
private data class ResultRow(
    val singles: Boolean,
    val period: String,
    val won: Boolean,
)
