// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.match

import org.skopeo.dto.match.CreateFixtureRequest
import org.skopeo.dto.match.MatchResultRequest
import org.skopeo.model.Capability
import org.skopeo.model.CreateFixtureCommand
import org.skopeo.model.Match
import org.skopeo.model.MatchFormat
import org.skopeo.model.MatchQuery
import org.skopeo.model.MatchSetResult
import org.skopeo.model.MatchStatus
import org.skopeo.model.NameType
import org.skopeo.model.TeamType
import org.skopeo.model.User
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.ForbiddenException
import org.skopeo.service.user.VerifiedFirebaseToken
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

private val STAFF_ROLES = setOf(Capability.HOST, Capability.ADMINISTRATOR)

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
) {
    fun createFixture(
        token: VerifiedFirebaseToken,
        request: CreateFixtureRequest,
    ): Match {
        val createdBy = requireStaff(token = token)
        val type = parseEnum(value = request.matchType) { TeamType.valueOf(value = it) }
        // Every MatchFormat value is supported; parseEnum already rejects unknown strings (400),
        // so no further allowlist check is needed (it would be unreachable dead code).
        val format = parseEnum(value = request.matchFormat) { MatchFormat.valueOf(value = it) }
        val matchDate = parseDate(value = request.matchDate)
        val team1Ids = request.team1.map(transform = ::parseUuid)
        val team2Ids = request.team2.map(transform = ::parseUuid)
        validateComposition(type = type, team1 = team1Ids, team2 = team2Ids)
        val team1Users = resolveRatedParticipants(ids = team1Ids)
        val team2Users = resolveRatedParticipants(ids = team2Ids)

        return matches.createFixture(
            command =
                CreateFixtureCommand(
                    matchType = type,
                    matchFormat = format,
                    matchDate = matchDate,
                    team1UserIds = team1Ids,
                    team2UserIds = team2Ids,
                    team1Name = teamName(users = team1Users),
                    team2Name = teamName(users = team2Users),
                    createdBy = createdBy,
                    venue = request.venue,
                    tournamentName = request.tournamentName,
                ),
        )
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
                format = match.matchFormat,
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
            MatchQuery.AWAITING_RESULTS -> matches.listAwaitingResults(asOf = LocalDate.now(), createdBy = scopedTo)
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

private fun validateComposition(
    type: TeamType,
    team1: List<UUID>,
    team2: List<UUID>,
) {
    val expected = if (type == TeamType.SINGLES) 1 else 2
    require(value = team1.size == expected && team2.size == expected) { "$type needs $expected player(s) per side" }
    val all = team1 + team2
    require(value = all.toSet().size == all.size) { "a player cannot appear more than once in a match" }
}

/** Derive per-set winners and the match winner from the raw scores; validates a clear outcome. */
private fun deriveOutcome(
    team1Id: UUID,
    team2Id: UUID,
    request: MatchResultRequest,
    format: MatchFormat,
): Pair<List<MatchSetResult>, UUID> {
    require(value = request.sets.isNotEmpty()) { "at least one set is required" }
    // A single-set match is exactly one set; the winner is simply the side with more games
    // (or the tiebreak), so any score the host enters (4-3, 6-5, 7-6, …) is accepted.
    require(value = format != MatchFormat.SINGLE_SET || request.sets.size == 1) {
        "a single-set match must have exactly one set"
    }
    var team1Sets = 0
    var team2Sets = 0
    val resolved =
        request.sets.mapIndexed { index, set ->
            require(value = set.team1Games >= 0 && set.team2Games >= 0) { "set ${index + 1}: games must be non-negative" }
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

private fun parseUuid(value: String): UUID =
    try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid user id '$value'", e)
    }

private fun parseDate(value: String): LocalDate =
    try {
        LocalDate.parse(value)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("Invalid matchDate '$value'; expected ISO-8601 (yyyy-MM-dd)", e)
    }

private fun <T> parseEnum(
    value: String,
    parse: (String) -> T,
): T =
    try {
        parse(value)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid value '$value'", e)
    }
