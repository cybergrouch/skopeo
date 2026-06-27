// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.match

import org.skopeo.dto.match.MatchResultRequest
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.Capability
import org.skopeo.model.CreateFixtureCommand
import org.skopeo.model.Match
import org.skopeo.model.MatchCalculationDetail
import org.skopeo.model.MatchPlayerCalculation
import org.skopeo.model.MatchQuery
import org.skopeo.model.MatchSetResult
import org.skopeo.model.MatchStatus
import org.skopeo.model.MatchType
import org.skopeo.model.NameType
import org.skopeo.model.TeamType
import org.skopeo.model.User
import org.skopeo.model.displayName
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.ResourceNotFoundException
import org.skopeo.service.audit.AuditService
import org.skopeo.service.user.ForbiddenException
import org.skopeo.service.user.VerifiedFirebaseToken
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

private val STAFF_ROLES = setOf(Capability.HOST, Capability.ADMINISTRATOR)

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
)

/**
 * Match fixtures & results. Creating fixtures, uploading results, and disabling are
 * HOST/ADMINISTRATOR actions; the oversight queries are ADMINISTRATOR-only. Recording a result
 * does NOT compute ratings — that's the separate calculation trigger (PR2b). Matches are
 * append-only: disabling (allowed only before a match is rated) plus a new record is how
 * corrections are made.
 */
class MatchService(
    private val matches: MatchRepository = MatchRepository(),
    private val ratings: RatingRepository = RatingRepository(),
    private val users: UserRepository = UserRepository(),
    private val audit: AuditService = AuditService(),
) {
    /** [request] is parsed, range-checked, and composition-validated at the route boundary (#116). */
    fun createFixture(
        token: VerifiedFirebaseToken,
        request: FixtureInput,
    ): Match {
        val createdBy = requireStaff(token = token)
        val team1Users = resolveRatedParticipants(ids = request.team1)
        val team2Users = resolveRatedParticipants(ids = request.team2)

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
        return match
    }

    @Suppress("ThrowsCount") // distinct guardrails: not-found, disabled, already-completed
    fun uploadResult(
        token: VerifiedFirebaseToken,
        matchId: UUID,
        request: MatchResultRequest,
    ): Match {
        val recordedBy = requireStaff(token = token)
        val match = matches.findById(matchId = matchId) ?: throw MatchNotFoundException(id = matchId)
        if (!match.isActive) throw MatchConflictException(message = "Match is disabled")
        if (match.status != MatchStatus.SCHEDULED) throw MatchConflictException(message = "Match already has results")
        val (resolvedSets, winner) =
            deriveOutcome(
                team1Id = match.team1.teamId,
                team2Id = match.team2.teamId,
                request = request,
            )
        // All reachable validations have passed; record before persisting (the located, SCHEDULED
        // match means addResult below won't be a no-op).
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
        return matches.addResult(
            matchId = matchId,
            sets = resolvedSets,
            winnerTeamId = winner,
            recordedBy = recordedBy,
            completedAt = LocalDateTime.now(),
        ) ?: throw MatchNotFoundException(id = matchId)
    }

    @Suppress("ThrowsCount") // distinct guardrails: not-found, rated-lock, not-found-on-update
    fun setActive(
        token: VerifiedFirebaseToken,
        matchId: UUID,
        active: Boolean,
    ): Match {
        requireStaff(token = token)
        val match = matches.findById(matchId = matchId) ?: throw MatchNotFoundException(id = matchId)
        if (!active && match.ratedAt != null) {
            throw MatchConflictException(message = "Cannot disable a match that has already been rated")
        }
        val disabledAt = if (active) null else LocalDateTime.now()
        return matches.setActive(matchId = matchId, active = active, disabledAt = disabledAt)
            ?: throw MatchNotFoundException(id = matchId)
    }

    fun getById(
        token: VerifiedFirebaseToken,
        matchId: UUID,
    ): Match {
        val match = matches.findById(matchId = matchId) ?: throw MatchNotFoundException(id = matchId)
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        val isStaff = caller?.capabilities?.any { it in STAFF_ROLES } == true
        val isParticipant = caller != null && caller.id in (match.team1.userIds + match.team2.userIds)
        if (!isStaff && !isParticipant) throw ForbiddenException()
        return match
    }

    /**
     * The match result plus the stored per-player calculation behind it (#97), for the detail view
     * a rating-history entry links to. Same participant-or-staff access as [getById]. Reads the
     * breakdown persisted at commit time — never recomputed — so it stays faithful even if the
     * algorithm constants change. Throws when the match has no committed calculation yet.
     */
    fun calculationDetail(
        token: VerifiedFirebaseToken,
        matchId: UUID,
    ): MatchCalculationDetail {
        val match = getById(token = token, matchId = matchId)
        val byUser = ratings.historyForMatches(matchIds = listOf(element = matchId)).associateBy { it.userId }
        if (byUser.isEmpty()) {
            throw ResourceNotFoundException(message = "No rating calculation has been recorded for match $matchId")
        }
        val names = displayNames(userIds = byUser.keys.toList())
        // Present players in team1-then-team2 order for a stable, intuitive layout.
        val order = match.team1.userIds + match.team2.userIds
        val players =
            byUser.values
                .sortedBy { order.indexOf(element = it.userId) }
                .map { entry -> MatchPlayerCalculation(userId = entry.userId, displayName = names[entry.userId], history = entry) }
        return MatchCalculationDetail(match = match, players = players)
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
    ): List<Match> {
        val caller = staffCaller(token = token)
        val scopedTo = if (caller.capabilities.contains(element = Capability.ADMINISTRATOR)) null else caller.id
        return when (view) {
            MatchQuery.PENDING_CALCULATION -> matches.listPendingCalculation(createdBy = scopedTo)
            MatchQuery.AWAITING_RESULTS -> matches.listAwaitingResults(createdBy = scopedTo)
        }
    }

    private fun requireStaff(token: VerifiedFirebaseToken): UUID = staffCaller(token = token).id

    private fun staffCaller(token: VerifiedFirebaseToken): User {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        if (caller == null || caller.capabilities.none { it in STAFF_ROLES }) throw ForbiddenException()
        return caller
    }

    private fun resolveRatedParticipants(ids: List<UUID>): List<User> =
        ids.map { id ->
            val user = users.findById(id = id) ?: throw IllegalArgumentException("Unknown user $id")
            require(value = user.isActive) { "User $id is not active" }
            requireNotNull(value = ratings.findCurrentRating(userId = id)) {
                "User $id has no rating yet (pending assessment)"
            }
            user
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
): Pair<List<MatchSetResult>, UUID> {
    var team1Sets = 0
    var team2Sets = 0
    val resolved =
        request.sets.mapIndexed { index, set ->
            val winner = setWinner(team1Id = team1Id, team2Id = team2Id, set = set, setNumber = index + 1)
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
    require(value = team1Sets != team2Sets) { "the match has no clear winner (sets are tied)" }
    return resolved to if (team1Sets > team2Sets) team1Id else team2Id
}

private fun setWinner(
    team1Id: UUID,
    team2Id: UUID,
    set: org.skopeo.dto.match.SetScoreRequest,
    setNumber: Int,
): UUID =
    when {
        set.team1Games > set.team2Games -> team1Id
        set.team2Games > set.team1Games -> team2Id
        set.tiebreakTeam1Points != null && set.tiebreakTeam2Points != null &&
            set.tiebreakTeam1Points != set.tiebreakTeam2Points ->
            if (set.tiebreakTeam1Points > set.tiebreakTeam2Points) team1Id else team2Id
        else -> throw IllegalArgumentException("set $setNumber has no clear winner")
    }

private fun teamName(users: List<User>): String =
    users.joinToString(separator = "/") { user ->
        user.names.firstOrNull { it.type == NameType.DISPLAY && it.isActive }?.value ?: "Player"
    }
