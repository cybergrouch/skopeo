// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.match

import kotlinx.serialization.Serializable
import org.skopeo.model.Match
import java.util.UUID

/** Body for `POST /api/v1/matches` — create a fixture (no results yet). */
@Serializable
data class CreateFixtureRequest(
    val matchFormat: String,
    val matchType: String,
    val matchDate: String,
    val team1: List<String>,
    val team2: List<String>,
    val venue: String? = null,
    val tournamentName: String? = null,
    /** When set, the fixture belongs to this event and both sides must be participants (#138). */
    val eventId: String? = null,
)

@Serializable
data class SetScoreRequest(
    val team1Games: Int,
    val team2Games: Int,
    val tiebreakTeam1Points: Int? = null,
    val tiebreakTeam2Points: Int? = null,
) {
    init {
        // Shape validation at the boundary (#116): games can never be negative.
        require(value = team1Games >= 0 && team2Games >= 0) { "games must be non-negative" }
    }
}

/** Body for `POST /api/v1/matches/{id}/result` — upload the set scores. */
@Serializable
data class MatchResultRequest(
    val sets: List<SetScoreRequest>,
) {
    init {
        // Shape validation at the boundary (#116): a result must report at least one set.
        require(value = sets.isNotEmpty()) { "at least one set is required" }
    }
}

/** Body for `PUT /api/v1/matches/{id}/state` — enable/disable (append-only corrections). */
@Serializable
data class MatchStateRequest(
    val isActive: Boolean,
)

@Serializable
data class MatchSideResponse(
    val teamId: String,
    val userIds: List<String>,
)

@Serializable
data class MatchSetResponse(
    val setNumber: Int,
    val team1Games: Int,
    val team2Games: Int,
    val winnerTeamId: String,
    val tiebreakTeam1Points: Int? = null,
    val tiebreakTeam2Points: Int? = null,
)

@Serializable
data class MatchResponse(
    val id: String,
    val publicCode: String,
    val matchFormat: String,
    val matchType: String,
    val matchDate: String,
    val status: String,
    val team1: MatchSideResponse,
    val team2: MatchSideResponse,
    val winnerTeamId: String? = null,
    val sets: List<MatchSetResponse>,
    val venue: String? = null,
    val tournamentName: String? = null,
    val isActive: Boolean,
    val completedAt: String? = null,
    val ratedAt: String? = null,
    val createdBy: String? = null,
    val recordedBy: String? = null,
    val eventId: String? = null,
)

fun Match.toResponse(): MatchResponse =
    MatchResponse(
        id = id.toString(),
        publicCode = publicCode,
        matchFormat = matchFormat.name,
        matchType = matchType.name,
        matchDate = matchDate.toString(),
        status = status.name,
        team1 = MatchSideResponse(teamId = team1.teamId.toString(), userIds = team1.userIds.map { it.toString() }),
        team2 = MatchSideResponse(teamId = team2.teamId.toString(), userIds = team2.userIds.map { it.toString() }),
        winnerTeamId = winnerTeamId?.toString(),
        sets =
            sets.map {
                MatchSetResponse(
                    setNumber = it.setNumber,
                    team1Games = it.team1Games,
                    team2Games = it.team2Games,
                    winnerTeamId = it.winnerTeamId.toString(),
                    tiebreakTeam1Points = it.tiebreakTeam1Points,
                    tiebreakTeam2Points = it.tiebreakTeam2Points,
                )
            },
        venue = venue,
        tournamentName = tournamentName,
        isActive = isActive,
        completedAt = completedAt?.toString(),
        ratedAt = ratedAt?.toString(),
        createdBy = createdBy?.toString(),
        recordedBy = recordedBy?.toString(),
        eventId = eventId?.toString(),
    )

/** One player on the public match page (#136): just a display name + shareable code, no ids/contacts. */
@Serializable
data class MatchPublicPlayer(
    val displayName: String? = null,
    val publicCode: String? = null,
)

/** A set's score on the public match page, expressed per side (no internal team ids). */
@Serializable
data class MatchPublicSet(
    val setNumber: Int,
    val team1Games: Int,
    val team2Games: Int,
    val tiebreakTeam1Points: Int? = null,
    val tiebreakTeam2Points: Int? = null,
)

/**
 * Read-only public summary of a match (#136), addressed by its public code. Players are resolved to
 * display name + code (so the page can link to their public profiles); the winner is named by side.
 */
@Serializable
data class MatchPublicResponse(
    val publicCode: String,
    val matchFormat: String,
    val matchType: String,
    val matchDate: String,
    val status: String,
    val team1: List<MatchPublicPlayer>,
    val team2: List<MatchPublicPlayer>,
    // The winning side, named relative to team1/team2: "TEAM1" | "TEAM2" | "NONE".
    val winner: String,
    val sets: List<MatchPublicSet>,
    val venue: String? = null,
    val tournamentName: String? = null,
)

/** Build the public response, resolving each side's players via [players] (id → name/code). */
fun Match.toPublicResponse(players: Map<UUID, MatchPublicPlayer>): MatchPublicResponse {
    fun side(userIds: List<UUID>) = userIds.map { players[it] ?: MatchPublicPlayer() }
    val winnerSide =
        when (winnerTeamId) {
            team1.teamId -> "TEAM1"
            team2.teamId -> "TEAM2"
            else -> "NONE"
        }
    return MatchPublicResponse(
        publicCode = publicCode,
        matchFormat = matchFormat.name,
        matchType = matchType.name,
        matchDate = matchDate.toString(),
        status = status.name,
        team1 = side(userIds = team1.userIds),
        team2 = side(userIds = team2.userIds),
        winner = winnerSide,
        sets =
            sets.map {
                MatchPublicSet(
                    setNumber = it.setNumber,
                    team1Games = it.team1Games,
                    team2Games = it.team2Games,
                    tiebreakTeam1Points = it.tiebreakTeam1Points,
                    tiebreakTeam2Points = it.tiebreakTeam2Points,
                )
            },
        venue = venue,
        tournamentName = tournamentName,
    )
}
