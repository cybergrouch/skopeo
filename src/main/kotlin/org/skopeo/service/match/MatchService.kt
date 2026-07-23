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
import org.skopeo.model.EventType
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
import org.skopeo.model.isDeleted
import org.skopeo.model.isExpired
import org.skopeo.model.pointsWindow
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.PointsBudgetRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import org.skopeo.service.user.VerifiedFirebaseToken
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

private val STAFF_ROLES = setOf(Capability.HOST, Capability.CLUB_OWNER, Capability.ADMINISTRATOR)

// Roles exempt from the expired-event data-entry gate (#310): administrators and club owners may
// still create fixtures / record results after an event has ended; a plain host may not.
private val EXPIRY_EXEMPT_ROLES = setOf(Capability.CLUB_OWNER, Capability.ADMINISTRATOR)

// Event types that carry a points budget/designation. Every event class rewards points now that
// OPEN_PLAY was unified with TOURNAMENT/LEAGUE (#403 Phase C; unified in feat/open-play-points-unify);
// designation applies whenever the event has a club. Only an event-less fixture designates no points.
private val BUDGETED_TYPES = setOf(EventType.TOURNAMENT, EventType.LEAGUE, EventType.OPEN_PLAY)

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
    /**
     * Points designated for the winner (#403 Phase C). Applies to a fixture on an event that carries a
     * points config (any type with a club; OPEN_PLAY unified); when omitted there, it defaults to
     * round(avg(event.min, event.max)). Kept null for a config-less or event-less fixture.
     */
    val designatedPoints: Int? = null,
    /**
     * The "award points for this match" checkbox (#466). Default (null/true) → the fixture awards points
     * on a points-awarding event (designation defaults as above). Explicit false → the match opts out:
     * no designation (null), so it awards no points even under a points-awarding event.
     */
    val awardPoints: Boolean? = null,
    /**
     * Optional per-side rating handicap (#486) in team-mean NTRP units, `0 < h <= 1.0`; null = none.
     * Validated at the boundary; deducted from that side for the rating-delta computation only.
     */
    val team1Handicap: BigDecimal? = null,
    val team2Handicap: BigDecimal? = null,
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
    private val budgets: PointsBudgetRepository = PointsBudgetRepository(),
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
            val event =
                request.eventId?.let { eventId ->
                    val loaded =
                        ensureNotNull(value = events.findById(id = eventId)) {
                            ServiceError.Validation(message = "Event $eventId not found")
                        }
                    ensureHostMayEnter(event = loaded, caller = caller).bind()
                    ensureEventNotFinalized(event = loaded).bind()
                    loaded
                }
            val team1Users = resolveRatedParticipants(ids = request.team1).bind()
            val team2Users = resolveRatedParticipants(ids = request.team2).bind()
            // Resolve + budget-check the point designation (#403 Phase C). Null for OPEN_PLAY / eventless.
            val designated = resolveDesignation(event = event, request = request).bind()

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
                            designatedPoints = designated,
                            team1Handicap = request.team1Handicap,
                            team2Handicap = request.team2Handicap,
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
            // A designated fixture logs its own designation entry (#403 Phase C): amount, team size, cost.
            designated?.let { points ->
                auditDesignation(actorId = createdBy, matchId = match.id, points = points, teamSize = request.team1.size)
            }
            match
        }

    /** Record the FIXTURE_POINTS_DESIGNATED audit entry (#403 Phase C): amount, team size, and cost. */
    private fun auditDesignation(
        actorId: UUID,
        matchId: UUID,
        points: Int,
        teamSize: Int,
    ) {
        audit.record(
            write =
                AuditWrite(
                    actorUserId = actorId,
                    action = AuditAction.FIXTURE_POINTS_DESIGNATED,
                    entityType = AuditEntityType.MATCH,
                    entityId = matchId,
                    summary = "Designated $points points for the winner",
                    details =
                        mapOf(
                            "designatedPoints" to points.toString(),
                            "teamSize" to teamSize.toString(),
                            "cost" to (points * teamSize).toString(),
                        ),
                ),
        )
    }

    /**
     * Resolve the point designation for a new fixture (#403 Phase C; OPEN_PLAY unified in
     * feat/open-play-points-unify). Points are carried only by an event that has a **config** (a points
     * window) — which, by the create rules, means an event with a club (the "no club ⇒ no points" rule).
     * For such a fixture the amount defaults to round(avg(event.min, event.max)) when omitted, is
     * validated to be an integer within [event.min, event.max], and — when the event has a club — is
     * checked cumulatively against the club's free budget: currentReserved + amount × team size must not
     * exceed the club's budgeted allocation for the type. An event-less fixture, or a fixture on a
     * config-less event (clubless / deferred config), designates nothing → null.
     */
    private fun resolveDesignation(
        event: Event?,
        request: FixtureInput,
    ): Either<ServiceError, Int?> =
        either {
            if (event == null || event.type !in BUDGETED_TYPES) {
                return@either null
            }
            // The "award points for this match" checkbox unchecked (#466): opt this fixture out even under
            // a points-awarding event → no designation, no reservation.
            if (request.awardPoints == false) {
                return@either null
            }
            // A config is written atomically (all four fields or none). No config → no points window →
            // no designation: this covers a clubless event and one whose config is still deferred.
            val window = event.pointsWindow() ?: return@either null
            val (min, max) = window
            val amount = request.designatedPoints ?: Math.round((min + max) / 2.0).toInt()
            ensure(condition = amount in min..max) {
                ServiceError.Validation(message = "Designated points must be an integer between $min and $max")
            }
            // The cumulative reservation budget check engages only when the event has a club (#403 Phase C).
            event.clubId?.let { clubId ->
                val teamSize = request.team1.size
                val reserved = budgets.sumReservedPoints(clubId = clubId, eventType = event.type)
                val budgeted = budgets.findBudget(clubId = clubId, eventType = event.type)
                ensure(condition = reserved + amount * teamSize <= budgeted) {
                    ServiceError.Validation(message = "Designation exceeds club budget for ${event.type}")
                }
            }
            amount
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
                ensureEventNotFinalized(event = event).bind()
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

    /**
     * Set (or clear) a fixture's designated points (#466 opt-in "award points for this match" checkbox).
     * Staff-only, only while the fixture is unrated, and only on an event that awards points (has a
     * config). A non-null amount is validated against the EVENT's [min, max] (never the global policy —
     * the event already conforms) plus the cumulative club-budget reserve check (excluding this fixture's
     * own current reservation). Null clears the designation → the match awards no points (reservation
     * released). Audited as FIXTURE_POINTS_DESIGNATED.
     */
    fun setDesignation(
        token: VerifiedFirebaseToken,
        matchId: UUID,
        designatedPoints: Int?,
    ): Either<ServiceError, Match> =
        either {
            val caller = staffCaller(token = token).bind()
            val match = matches.findById(matchId = matchId).bind()
            ensure(condition = match.isActive) { ServiceError.Conflict(message = "Match is disabled") }
            ensure(condition = match.ratedAt == null) {
                ServiceError.Conflict(message = "Cannot change points on a match that has already been rated")
            }
            val eventId =
                ensureNotNull(value = match.eventId) {
                    ServiceError.Validation(message = "Only an evented fixture can designate points")
                }
            val event =
                ensureNotNull(value = events.findById(id = eventId)) { ServiceError.NotFound(message = "Event $eventId not found") }
            ensureHostMayEnter(event = event, caller = caller).bind()
            ensureEventNotFinalized(event = event).bind()
            val resolved = resolveDesignationUpdate(event = event, match = match, amount = designatedPoints).bind()
            val updated = matches.setDesignatedPoints(matchId = matchId, designatedPoints = resolved).bind()
            // Log the designation set/clear; a cleared designation is a size-0 cost entry.
            auditDesignation(actorId = caller.id, matchId = matchId, points = resolved ?: 0, teamSize = match.team1.userIds.size)
            updated
        }

    /**
     * Set (or clear) a fixture's per-side rating handicaps (#486). Staff-only and only while the fixture
     * is unrated (a rated match is frozen). Each value has already been range-validated at the boundary
     * (`0 < h <= 1.0`); a null side clears that side's handicap. Audited as FIXTURE_HANDICAP_SET.
     */
    fun setHandicaps(
        token: VerifiedFirebaseToken,
        matchId: UUID,
        team1Handicap: BigDecimal?,
        team2Handicap: BigDecimal?,
    ): Either<ServiceError, Match> =
        either {
            val caller = staffCaller(token = token).bind()
            val match = matches.findById(matchId = matchId).bind()
            ensure(condition = match.isActive) { ServiceError.Conflict(message = "Match is disabled") }
            ensure(condition = match.ratedAt == null) {
                ServiceError.Conflict(message = "Cannot change the handicap on a match that has already been rated")
            }
            val updated =
                matches.setHandicaps(matchId = matchId, team1Handicap = team1Handicap, team2Handicap = team2Handicap).bind()
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = caller.id,
                        action = AuditAction.FIXTURE_HANDICAP_SET,
                        entityType = AuditEntityType.MATCH,
                        entityId = matchId,
                        summary = "Set per-side handicap on the fixture",
                        details =
                            mapOf(
                                "team1Handicap" to (team1Handicap?.toPlainString() ?: "none"),
                                "team2Handicap" to (team2Handicap?.toPlainString() ?: "none"),
                            ),
                    ),
            )
            updated
        }

    /**
     * Resolve a fixture designation UPDATE (#466). Null clears (no points). A non-null amount must be an
     * event that awards points, be within the event's [min, max], and pass the cumulative reserve check —
     * which excludes this fixture's OWN current reservation so re-setting the same amount never self-blocks.
     */
    private fun resolveDesignationUpdate(
        event: Event,
        match: Match,
        amount: Int?,
    ): Either<ServiceError, Int?> =
        either {
            if (amount == null) return@either null
            val window =
                ensureNotNull(value = event.pointsWindow()) {
                    ServiceError.Validation(message = "This event awards no points")
                }
            val (min, max) = window
            ensure(condition = amount in min..max) {
                ServiceError.Validation(message = "Designated points must be an integer between $min and $max")
            }
            event.clubId?.let { clubId ->
                val teamSize = match.team1.userIds.size
                // Exclude this fixture's own current reservation, which is already in the emergent sum.
                val ownReserved = (match.designatedPoints ?: 0) * teamSize
                val reserved = budgets.sumReservedPoints(clubId = clubId, eventType = event.type) - ownReserved
                val budgeted = budgets.findBudget(clubId = clubId, eventType = event.type)
                ensure(condition = reserved + amount * teamSize <= budgeted) {
                    ServiceError.Validation(message = "Designation exceeds club budget for ${event.type}")
                }
            }
            amount
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
                    MatchPublicPlayer(
                        displayName = user.displayName(),
                        publicCode = user.publicCode,
                        isPlaceholder = user.placeholder,
                        isDeleted = user.isDeleted(),
                    )
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
                match.eventId?.let { eventId ->
                    val owning = events.getById(id = eventId)
                    MatchPublicEvent(publicCode = owning.publicCode, name = owning.name)
                }
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
                        MatchPublicPlayer(
                            displayName = user.displayName(),
                            publicCode = user.publicCode,
                            isPlaceholder = user.placeholder,
                            isDeleted = user.isDeleted(),
                        )
                    },
            )
        }
    }

    /**
     * Head-to-head record between a singles match's two players (#188): the win tally and prior
     * completed meetings, newest first. The [meetings] list is the *prior* meetings (this match is not
     * repeated there — it's already the page's subject), but the win tally **includes** this match when
     * it's decided (#339), so the head-to-head reflects the result being viewed. Shown for every singles
     * match, including a first-ever meeting (empty [meetings], tally from this match) (#366); null only
     * when the match is not singles (one player per side). Both players are already in [usersById]; wins
     * and set scores are oriented to team1/team2.
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
                .filter { meeting -> wereOpponents(meeting = meeting, playerA = team1Id, playerB = team2Id) }
                .map { headToHeadEntry(meeting = it, team1Id = team1Id, team2Id = team2Id, codes = codes) }
        // The current match counts toward the tally once it has a result (#339); it stays out of the list.
        val current =
            match
                .takeIf { it.status == MatchStatus.COMPLETED }
                ?.let { headToHeadEntry(meeting = it, team1Id = team1Id, team2Id = team2Id, codes = codes) }
        val forTally = prior + listOfNotNull(element = current)
        return MatchPublicHeadToHead(
            team1Wins = forTally.count { entry -> entry.winnerPublicCode == codes[team1Id] },
            team2Wins = forTally.count { entry -> entry.winnerPublicCode == codes[team2Id] },
            meetings = prior,
        )
    }

    /**
     * True when [playerA] and [playerB] faced each other as opponents in [meeting] — one on each side,
     * in either orientation. Doubles partners on the same team are excluded, which is why a meeting
     * surfaced by `listBetweenUsers` isn't automatically a head-to-head (#285). Written as a single
     * membership check per side (rather than a compound boolean) so each side/orientation is coverable.
     */
    private fun wereOpponents(
        meeting: Match,
        playerA: UUID,
        playerB: UUID,
    ): Boolean {
        val aOnTeam1 = playerA in meeting.team1.userIds
        val aOnTeam2 = playerA in meeting.team2.userIds
        val bOnTeam1 = playerB in meeting.team1.userIds
        val bOnTeam2 = playerB in meeting.team2.userIds
        return (aOnTeam1 && bOnTeam2) || (aOnTeam2 && bOnTeam1)
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
        val placeholderByUser = usersById.mapValues { (_, user) -> user.placeholder }
        val deletedByUser = usersById.mapValues { (_, user) -> user.isDeleted() }
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
                    isPlaceholder = placeholderByUser[history.userId] ?: false,
                    isDeleted = deletedByUser[history.userId] ?: false,
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

    /**
     * Reject entering matches on a finalized event (#403): finalize is terminal and closes the event
     * to further changes, so a fixture cannot be created on it and a result cannot be recorded.
     */
    private fun ensureEventNotFinalized(event: Event): Either<ServiceError, Unit> =
        either {
            ensure(condition = !event.isFinalized) { ServiceError.Validation(message = "Event is finalized") }
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
