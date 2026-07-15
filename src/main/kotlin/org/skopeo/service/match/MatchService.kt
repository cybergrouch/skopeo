// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.match

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.skopeo.dto.match.MatchPublicEvent
import org.skopeo.dto.match.MatchPublicHeadToHead
import org.skopeo.dto.match.MatchPublicHeadToHeadEntry
import org.skopeo.dto.match.MatchPublicPlayer
import org.skopeo.dto.match.MatchPublicRatingChange
import org.skopeo.dto.match.MatchPublicResponse
import org.skopeo.dto.match.MatchPublicSet
import org.skopeo.dto.match.MatchResultRequest
import org.skopeo.dto.match.UpcomingMatchResponse
import org.skopeo.dto.match.toPublicResponse
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.Capability
import org.skopeo.model.CreateFixtureCommand
import org.skopeo.model.Event
import org.skopeo.model.Match
import org.skopeo.model.MatchCalculationDetail
import org.skopeo.model.MatchPlayerCalculation
import org.skopeo.model.MatchQuery
import org.skopeo.model.MatchSetResult
import org.skopeo.model.MatchStatus
import org.skopeo.model.MatchType
import org.skopeo.model.NameType
import org.skopeo.model.ServiceError
import org.skopeo.model.TeamType
import org.skopeo.model.User
import org.skopeo.model.displayName
import org.skopeo.model.isExpired
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import org.skopeo.service.user.VerifiedFirebaseToken
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

private val STAFF_ROLES = setOf(Capability.HOST, Capability.CLUB_OWNER, Capability.ADMINISTRATOR)

// Roles exempt from the expired-event data-entry gate (#310): administrators and club owners may
// still create fixtures / record results after an event has ended; a plain host may not.
private val EXPIRY_EXEMPT_ROLES = setOf(Capability.CLUB_OWNER, Capability.ADMINISTRATOR)

/**
 * Fixture-creation input resolved at the route boundary (#116): the match format/type enums are
 * parsed, the date is parsed, the participant ids are valid UUIDs, and the team composition (players
 * per side, no repeats) is validated. The service then enforces only business rules (staff auth,
 * participant existence/active/rated). [matchFormat] is SINGLES/DOUBLES; [matchType] is the
 * competitive context (open play, league, tournament, …) that scales the rating change (#108).
 */
data class FixtureInput(
    val matchFormat: TeamType,
    val matchType: MatchType,
    val matchDate: LocalDate,
    val team1: List<UUID>,
    val team2: List<UUID>,
    val venue: String? = null,
    val tournamentName: String? = null,
    /** When set, the fixture belongs to this event and both sides must be event participants (#138). */
    val eventId: UUID? = null,
)

/**
 * Match fixtures & results. Creating fixtures, uploading results, and disabling are
 * HOST/ADMINISTRATOR actions; the oversight queries are ADMINISTRATOR-only. Recording a result
 * does NOT compute ratings — that's the separate calculation trigger (PR2b). Matches are
 * append-only: disabling (allowed only before a match is rated) plus a new record is how
 * corrections are made.
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
class MatchService(
    private val matches: MatchRepository = MatchRepository(),
    private val ratings: RatingRepository = RatingRepository(),
    private val users: UserRepository = UserRepository(),
    private val events: EventRepository = EventRepository(),
    private val audit: AuditService = AuditService(),
) {
    /** [request] is parsed, range-checked, and composition-validated at the route boundary (#116). */
    fun createFixture(
        token: VerifiedFirebaseToken,
        request: FixtureInput,
    ): Either<ServiceError, Match> =
        either {
            val caller = staffCaller(token = token).bind()
            val createdBy = caller.id
            ensureEventParticipants(request = request).bind()
            // A HOST cannot add fixtures to an event that has ended; an ADMINISTRATOR still can (#310).
            request.eventId?.let { eventId ->
                val event =
                    ensureNotNull(value = events.findById(id = eventId)) { ServiceError.Validation(message = "Event $eventId not found") }
                ensureHostMayEnter(event = event, caller = caller).bind()
            }
            val team1Users = resolveRatedParticipants(ids = request.team1).bind()
            val team2Users = resolveRatedParticipants(ids = request.team2).bind()

            val match =
                matches.createFixture(
                    command =
                        CreateFixtureCommand(
                            matchFormat = request.matchFormat,
                            matchType = request.matchType,
                            matchDate = request.matchDate,
                            team1UserIds = request.team1,
                            team2UserIds = request.team2,
                            team1Name = teamName(users = team1Users),
                            team2Name = teamName(users = team2Users),
                            createdBy = createdBy,
                            venue = request.venue,
                            tournamentName = request.tournamentName,
                            eventId = request.eventId,
                        ),
                )
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = createdBy,
                        action = AuditAction.MATCH_FIXTURE_CREATED,
                        entityType = AuditEntityType.MATCH,
                        entityId = match.id,
                        summary = "Created a ${request.matchFormat.name} fixture on ${match.matchDate}",
                        details =
                            mapOf(
                                "matchFormat" to request.matchFormat.name,
                                "matchType" to request.matchType.name,
                                "matchDate" to match.matchDate.toString(),
                            ),
                    ),
            )
            match
        }

    fun uploadResult(
        token: VerifiedFirebaseToken,
        matchId: UUID,
        request: MatchResultRequest,
    ): Either<ServiceError, Match> =
        either {
            val caller = staffCaller(token = token).bind()
            val recordedBy = caller.id
            val match = matches.findById(matchId = matchId).bind()
            ensure(condition = match.isActive) { ServiceError.Conflict(message = "Match is disabled") }
            // Results can be recorded on a scheduled fixture and re-recorded (edited) on a completed one,
            // but only while the match is still unrated — a rated result is frozen.
            ensure(condition = match.ratedAt == null) {
                ServiceError.Conflict(message = "Cannot edit a match that has already been rated")
            }
            // A HOST cannot record results on an event that has ended; an ADMINISTRATOR still can (#310).
            match.eventId?.let { eventId ->
                val event =
                    ensureNotNull(value = events.findById(id = eventId)) { ServiceError.NotFound(message = "Event $eventId not found") }
                ensureHostMayEnter(event = event, caller = caller).bind()
            }
            val (resolvedSets, winner) =
                deriveOutcome(
                    team1Id = match.team1.teamId,
                    team2Id = match.team2.teamId,
                    request = request,
                ).bind()
            // All reachable validations have passed; record the audit entry before persisting (the
            // located, active, unrated match means addResult below won't be a no-op).
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = recordedBy,
                        action = AuditAction.MATCH_RESULT_RECORDED,
                        entityType = AuditEntityType.MATCH,
                        entityId = matchId,
                        summary = "Recorded a match result",
                        details = mapOf("matchId" to matchId.toString(), "winnerTeamId" to winner.toString()),
                    ),
            )
            matches
                .addResult(
                    matchId = matchId,
                    sets = resolvedSets,
                    winnerTeamId = winner,
                    recordedBy = recordedBy,
                    completedAt = LocalDateTime.now(),
                ).bind()
        }

    fun setActive(
        token: VerifiedFirebaseToken,
        matchId: UUID,
        active: Boolean,
    ): Either<ServiceError, Match> =
        either {
            requireStaff(token = token).bind()
            val match = matches.findById(matchId = matchId).bind()
            ensure(condition = active || match.ratedAt == null) {
                ServiceError.Conflict(message = "Cannot disable a match that has already been rated")
            }
            val disabledAt = if (active) null else LocalDateTime.now()
            matches.setActive(matchId = matchId, active = active, disabledAt = disabledAt).bind()
        }

    /**
     * Set the manual calculation order for a set of same-date matches (#331/#332). Staff-only. The
     * rating calculation orders by match date first, so a manual drag only re-sequences matches that
     * share a date — hence the same-date guard. Rated matches are frozen, so they can't be reordered.
     * [matchIds] is the desired order; each gets calc_sequence = its index.
     */
    fun reorder(
        token: VerifiedFirebaseToken,
        matchIds: List<UUID>,
    ): Either<ServiceError, Unit> =
        either {
            requireStaff(token = token).bind()
            ensure(condition = matchIds.isNotEmpty()) { ServiceError.Validation(message = "No matches to reorder") }
            ensure(condition = matchIds.size == matchIds.distinct().size) {
                ServiceError.Validation(message = "Duplicate match ids in the reorder request")
            }
            val loaded = matchIds.map { matches.findById(matchId = it).bind() }
            ensure(condition = loaded.all { it.isActive }) {
                ServiceError.Validation(message = "Cannot reorder a disabled match")
            }
            ensure(condition = loaded.all { it.ratedAt == null }) {
                ServiceError.Conflict(message = "Cannot reorder a match that has already been rated")
            }
            ensure(condition = loaded.map { it.matchDate }.toSet().size == 1) {
                ServiceError.Validation(message = "Only matches on the same date can be reordered")
            }
            matches.reorderCalcSequence(matchIds = matchIds)
        }

    fun getById(
        token: VerifiedFirebaseToken,
        matchId: UUID,
    ): Either<ServiceError, Match> =
        either {
            val match = matches.findById(matchId = matchId).bind()
            val caller = users.findByFirebaseUid(firebaseUid = token.uid)
            val isStaff = caller != null && caller.capabilities.any { it in STAFF_ROLES }
            val isParticipant = caller != null && caller.id in (match.team1.userIds + match.team2.userIds)
            ensure(condition = isStaff || isParticipant) { ServiceError.Forbidden() }
            match
        }

    /**
     * Read-only public summary of a match by its public code (#136). Visible to any authenticated
     * user — the same "public" semantics as a player's public profile — with players resolved to
     * name + code. A [ServiceError.NotFound] if the code resolves to no active match.
     */
    fun publicByCode(
        token: VerifiedFirebaseToken?,
        code: String,
    ): Either<ServiceError, MatchPublicResponse> =
        either {
            val match = matches.findByPublicCode(code = code)
            ensureNotNull(value = match) { ServiceError.NotFound(message = "Match $code not found") }
            val ids = match.team1.userIds + match.team2.userIds
            val usersById = users.findAllByIds(ids = ids).associateBy { it.id }
            val players =
                usersById.mapValues { (_, user) ->
                    MatchPublicPlayer(displayName = user.displayName(), publicCode = user.publicCode)
                }
            // Once rated, surface the per-player rating change (#136): bands for everyone, precise
            // rates only for RATER/ADMINISTRATOR viewers.
            val ratingChanges =
                if (match.ratedAt != null) ratingChangesFor(match = match, usersById = usersById, token = token) else null
            // Prior meetings between the same two players (#188), if any.
            val headToHead = headToHeadFor(match = match, usersById = usersById)
            // The owning event (#358), if the match belongs to one — resolved to its shareable code + name
            // so the public page can link to the event. Null for eventless (open-play) matches.
            val event =
                match.eventId
                    ?.let { events.findById(id = it) }
                    ?.let { MatchPublicEvent(publicCode = it.publicCode, name = it.name) }
            match.toPublicResponse(players = players, ratingChanges = ratingChanges, headToHead = headToHead, event = event)
        }

    /**
     * The authenticated caller's upcoming matches (#251): their SCHEDULED fixtures dated today or later,
     * soonest first, for the private profile. Owner-only — an unprovisioned caller simply has none.
     */
    fun upcomingForCaller(token: VerifiedFirebaseToken): Either<ServiceError, List<UpcomingMatchResponse>> =
        either {
            val caller = users.findByFirebaseUid(firebaseUid = token.uid)
            if (caller == null) emptyList() else upcomingMatchesOf(userId = caller.id)
        }

    private fun upcomingMatchesOf(userId: UUID): List<UpcomingMatchResponse> {
        val today = LocalDate.now()
        val upcoming =
            matches
                .listByUser(userId = userId)
                .filter { it.status == MatchStatus.SCHEDULED && !it.matchDate.isBefore(today) }
                .sortedBy { it.matchDate }
        val playersById =
            users.findAllByIds(ids = upcoming.flatMap { it.team1.userIds + it.team2.userIds }.distinct()).associateBy { it.id }
        return upcoming.map { match ->
            val callerOnTeam1 = userId in match.team1.userIds
            val opponentIds = if (callerOnTeam1) match.team2.userIds else match.team1.userIds
            UpcomingMatchResponse(
                publicCode = match.publicCode,
                matchDate = match.matchDate.toString(),
                matchType = match.matchType.name,
                venue = match.venue,
                opponents =
                    opponentIds.map { id ->
                        val user = playersById.getValue(key = id)
                        MatchPublicPlayer(displayName = user.displayName(), publicCode = user.publicCode)
                    },
            )
        }
    }

    /**
     * Head-to-head record between a singles match's two players (#188): the win tally and prior
     * completed meetings, newest first. The [meetings] list is the *prior* meetings (this match is not
     * repeated there — it's already the page's subject), but the win tally **includes** this match when
     * it's decided (#339), so the head-to-head reflects the result being viewed. Null when the match is
     * not singles (one player per side) or there are no prior completed meetings. Both players are
     * already in [usersById]; wins and set scores are oriented to team1/team2.
     */
    private fun headToHeadFor(
        match: Match,
        usersById: Map<UUID, User>,
    ): MatchPublicHeadToHead? {
        if (match.team1.userIds.size != 1 || match.team2.userIds.size != 1) return null
        val team1Id = match.team1.userIds.first()
        val team2Id = match.team2.userIds.first()
        val codes = usersById.mapValues { (_, user) -> user.publicCode }
        val prior =
            matches
                .listBetweenUsers(userIdA = team1Id, userIdB = team2Id)
                .filter { it.id != match.id && it.status == MatchStatus.COMPLETED }
                // Count only meetings where the two were opponents (on opposite sides) — never partners
                // on the same doubles team, which listBetweenUsers can otherwise surface (#285).
                .filter { meeting ->
                    (team1Id in meeting.team1.userIds && team2Id in meeting.team2.userIds) ||
                        (team1Id in meeting.team2.userIds && team2Id in meeting.team1.userIds)
                }
                .map { headToHeadEntry(meeting = it, team1Id = team1Id, team2Id = team2Id, codes = codes) }
        // The current match counts toward the tally once it has a result (#339); it stays out of the list.
        val current =
            match
                .takeIf { it.status == MatchStatus.COMPLETED }
                ?.let { headToHeadEntry(meeting = it, team1Id = team1Id, team2Id = team2Id, codes = codes) }
        val forTally = prior + listOfNotNull(element = current)
        return prior
            .takeIf { it.isNotEmpty() }
            ?.let {
                MatchPublicHeadToHead(
                    team1Wins = forTally.count { entry -> entry.winnerPublicCode == codes[team1Id] },
                    team2Wins = forTally.count { entry -> entry.winnerPublicCode == codes[team2Id] },
                    meetings = it,
                )
            }
    }

    /**
     * Per-player rating changes for a rated match. Bands ([previousLevel]/[newLevel]) are public; the
     * precise rates (6-dp) are included only when the viewer holds RATER or ADMINISTRATOR. Players are
     * ordered team1-then-team2 (mirroring [calculationDetail]); empty when no history is recorded. Names
     * and codes are read from value-maps keyed by user id, resolved from the already-loaded [usersById].
     */
    private fun ratingChangesFor(
        match: Match,
        usersById: Map<UUID, User>,
        token: VerifiedFirebaseToken?,
    ): List<MatchPublicRatingChange> {
        val revealRates = callerCanSeeRates(token = token)
        val names = usersById.mapValues { (_, user) -> user.displayName() }
        val codes = usersById.mapValues { (_, user) -> user.publicCode }
        val order = match.team1.userIds + match.team2.userIds
        // Each player's *current* rating confidence (#343), shown beside the historical band change.
        val confidenceByUser =
            ratings.findCurrentRatings(userIds = order).mapValues { (_, rating) -> rating.confidence.toPlainString() }
        return ratings
            .historyForMatches(matchIds = listOf(element = match.id))
            .sortedBy { order.indexOf(element = it.userId) }
            .map { history ->
                MatchPublicRatingChange(
                    publicCode = codes[history.userId],
                    displayName = names[history.userId],
                    previousLevel = history.previousLevel,
                    newLevel = history.newLevel,
                    previousRating = if (revealRates) history.previousRating.toPlainString() else null,
                    newRating = if (revealRates) history.newRating.toPlainString() else null,
                    ratingChange = if (revealRates) history.ratingChange.toPlainString() else null,
                    confidence = confidenceByUser[history.userId],
                )
            }
    }

    /** Whether the viewer may see precise rates (RATER or ADMINISTRATOR); anonymous (#193) ⇒ false, bands only. */
    private fun callerCanSeeRates(token: VerifiedFirebaseToken?): Boolean {
        val caller = token?.let { users.findByFirebaseUid(firebaseUid = it.uid) } ?: return false
        return caller.capabilities.any { it == Capability.RATER || it == Capability.ADMINISTRATOR }
    }

    /**
     * The match result plus the stored per-player calculation behind it (#97), for the detail view
     * a rating-history entry links to. Same participant-or-staff access as [getById]. Reads the
     * breakdown persisted at commit time — never recomputed — so it stays faithful even if the
     * algorithm constants change. A [ServiceError.NotFound] when the match has no committed
     * calculation yet.
     */
    fun calculationDetail(
        token: VerifiedFirebaseToken,
        matchId: UUID,
    ): Either<ServiceError, MatchCalculationDetail> =
        either {
            val match = getById(token = token, matchId = matchId).bind()
            val byUser = ratings.historyForMatches(matchIds = listOf(element = matchId)).associateBy { it.userId }
            ensure(condition = byUser.isNotEmpty()) {
                ServiceError.NotFound(message = "No rating calculation has been recorded for match $matchId")
            }
            val names = displayNames(userIds = byUser.keys.toList())
            // Present players in team1-then-team2 order for a stable, intuitive layout.
            val order = match.team1.userIds + match.team2.userIds
            val players =
                byUser.values
                    .sortedBy { order.indexOf(element = it.userId) }
                    .map { entry -> MatchPlayerCalculation(userId = entry.userId, displayName = names[entry.userId], history = entry) }
            MatchCalculationDetail(match = match, players = players)
        }

    private fun displayNames(userIds: List<UUID>): Map<UUID, String?> =
        users.findAllByIds(ids = userIds).associate { it.id to it.displayName() }

    /**
     * Oversight lists for staff. An ADMINISTRATOR sees every match in the view; a HOST sees only
     * the fixtures they created (so they can find their own matches awaiting results).
     */
    fun query(
        token: VerifiedFirebaseToken,
        view: MatchQuery,
        eventId: UUID? = null,
    ): Either<ServiceError, List<Match>> =
        either {
            val caller = staffCaller(token = token).bind()
            val scopedTo = if (caller.capabilities.contains(element = Capability.ADMINISTRATOR)) null else caller.id
            when (view) {
                // An event scope (#138) shows that event's recorded-but-unrated fixtures (editable until
                // the rating calculation runs) to any staff member; otherwise the admin oversight list.
                MatchQuery.PENDING_CALCULATION ->
                    if (eventId != null) {
                        matches.listPendingCalculation(eventId = eventId)
                    } else {
                        matches.listPendingCalculation(createdBy = scopedTo)
                    }
                // An event scope (#138) shows that event's awaiting fixtures to any staff member.
                MatchQuery.AWAITING_RESULTS ->
                    if (eventId != null) {
                        matches.listAwaitingResults(eventId = eventId)
                    } else {
                        matches.listAwaitingResults(createdBy = scopedTo)
                    }
                // All of an event's completed fixtures (rated or not), so a rated match stays on view as
                // a read-only record (#138). Event-scoped only.
                MatchQuery.RESULTS -> eventId?.let { matches.listResultsByEvent(eventId = it) }.orEmpty()
            }
        }

    /**
     * When a fixture is scoped to an event (#138), the event must exist and BOTH sides must be event
     * participants — the hard constraint behind the participant-scoped player search. No-op otherwise.
     */
    private fun ensureEventParticipants(request: FixtureInput): Either<ServiceError, Unit> {
        val eventId = request.eventId ?: return Unit.right()
        val event = events.findById(id = eventId)
        val players = request.team1 + request.team2
        return when {
            event == null -> ServiceError.Validation(message = "Event $eventId not found").left()
            players.all { it in event.participantIds.toSet() } -> Unit.right()
            else -> ServiceError.Validation(message = "All players must be participants of the event").left()
        }
    }

    private fun requireStaff(token: VerifiedFirebaseToken): Either<ServiceError, UUID> = staffCaller(token = token).map { it.id }

    /**
     * Gate host data entry on an event (#310): once the event has ended, a plain HOST may no longer
     * create fixtures or record results on it — only an ADMINISTRATOR or a CLUB_OWNER may. A
     * [ServiceError.Conflict] otherwise.
     */
    private fun ensureHostMayEnter(
        event: Event,
        caller: User,
    ): Either<ServiceError, Unit> =
        either {
            val exempt = caller.capabilities.any { it in EXPIRY_EXEMPT_ROLES }
            ensure(condition = exempt || !event.isExpired(asOf = LocalDate.now())) {
                ServiceError.Conflict(message = "This event has ended; only an administrator or club owner can modify it.")
            }
        }

    private fun staffCaller(token: VerifiedFirebaseToken): Either<ServiceError, User> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        return if (caller == null || caller.capabilities.none { it in STAFF_ROLES }) ServiceError.Forbidden().left() else caller.right()
    }

    private fun resolveRatedParticipants(ids: List<UUID>): Either<ServiceError, List<User>> =
        either {
            ids.map { id ->
                val user = users.findById(id = id).mapLeft { ServiceError.Validation(message = "Unknown user $id") }.bind()
                ensure(condition = user.isActive) { ServiceError.Validation(message = "User $id is not active") }
                ensure(condition = ratings.findCurrentRating(userId = id) != null) {
                    ServiceError.Validation(message = "User $id has no rating yet (pending assessment)")
                }
                user
            }
        }
}

/**
 * Derive per-set winners and the match winner from the raw scores. The score *shape* (non-negative
 * games, at least one set) is validated at the boundary (#116, in the DTO); what remains here is
 * outcome derivation against the persisted match — each set must have a decisive score, and the sets
 * must not be tied (whoever wins more sets wins the match).
 */
private fun deriveOutcome(
    team1Id: UUID,
    team2Id: UUID,
    request: MatchResultRequest,
): Either<ServiceError, Pair<List<MatchSetResult>, UUID>> =
    either {
        var team1Sets = 0
        var team2Sets = 0
        val resolved =
            request.sets.mapIndexed { index, set ->
                val winner = setWinner(team1Id = team1Id, team2Id = team2Id, set = set, setNumber = index + 1).bind()
                if (winner == team1Id) team1Sets++ else team2Sets++
                MatchSetResult(
                    setNumber = index + 1,
                    team1Games = set.team1Games,
                    team2Games = set.team2Games,
                    winnerTeamId = winner,
                    tiebreakTeam1Points = set.tiebreakTeam1Points,
                    tiebreakTeam2Points = set.tiebreakTeam2Points,
                )
            }
        ensure(condition = team1Sets != team2Sets) { ServiceError.Validation(message = "the match has no clear winner (sets are tied)") }
        resolved to if (team1Sets > team2Sets) team1Id else team2Id
    }

private fun setWinner(
    team1Id: UUID,
    team2Id: UUID,
    set: org.skopeo.dto.match.SetScoreRequest,
    setNumber: Int,
): Either<ServiceError, UUID> =
    when {
        set.team1Games > set.team2Games -> team1Id.right()
        set.team2Games > set.team1Games -> team2Id.right()
        set.tiebreakTeam1Points != null && set.tiebreakTeam2Points != null &&
            set.tiebreakTeam1Points != set.tiebreakTeam2Points ->
            (if (set.tiebreakTeam1Points > set.tiebreakTeam2Points) team1Id else team2Id).right()
        else -> ServiceError.Validation(message = "set $setNumber has no clear winner").left()
    }

private fun teamName(users: List<User>): String =
    users.joinToString(separator = "/") { user ->
        user.names.firstOrNull { it.type == NameType.DISPLAY && it.isActive }?.value ?: "Player"
    }

/** Orient one set's games/tiebreaks to a reference player's side: team1 when [refIsTeam1] (#188). */
private fun orientSet(
    set: MatchSetResult,
    refIsTeam1: Boolean,
): MatchPublicSet =
    MatchPublicSet(
        setNumber = set.setNumber,
        team1Games = if (refIsTeam1) set.team1Games else set.team2Games,
        team2Games = if (refIsTeam1) set.team2Games else set.team1Games,
        tiebreakTeam1Points = if (refIsTeam1) set.tiebreakTeam1Points else set.tiebreakTeam2Points,
        tiebreakTeam2Points = if (refIsTeam1) set.tiebreakTeam2Points else set.tiebreakTeam1Points,
    )

/**
 * Build a head-to-head entry for [meeting] (#188), oriented to the reference player [team1Id] (the
 * viewed match's team1 player): set scores flip when the reference player was on team2, and the winner
 * is resolved to the winning player's public code via [codes].
 */
private fun headToHeadEntry(
    meeting: Match,
    team1Id: UUID,
    team2Id: UUID,
    codes: Map<UUID, String>,
): MatchPublicHeadToHeadEntry {
    val refIsTeam1 = team1Id in meeting.team1.userIds
    // Attribute the win by which SIDE the reference player was on, not by the winning team's first
    // player — so doubles meetings (two players a side) count for the right head-to-head player (#285).
    // Meetings are filtered to COMPLETED, so the winner is always team1 or team2.
    val refTeamId = if (refIsTeam1) meeting.team1.teamId else meeting.team2.teamId
    val refSideWon = meeting.winnerTeamId == refTeamId
    return MatchPublicHeadToHeadEntry(
        publicCode = meeting.publicCode,
        matchDate = meeting.matchDate.toString(),
        status = meeting.status.name,
        rated = meeting.ratedAt != null,
        matchFormat = meeting.matchFormat.name,
        sets = meeting.sets.map { orientSet(set = it, refIsTeam1 = refIsTeam1) },
        winnerPublicCode = if (refSideWon) codes[team1Id] else codes[team2Id],
    )
}
