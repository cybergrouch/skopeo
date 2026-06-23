// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.match

import kotlinx.serialization.Serializable
import org.skopeo.model.Match

/** Body for `POST /api/v1/matches` — create a fixture (no results yet). */
@Serializable
data class CreateFixtureRequest(
    val ratingSystem: String,
    val matchType: String,
    val matchFormat: String,
    val matchDate: String,
    val team1: List<String>,
    val team2: List<String>,
    val venue: String? = null,
    val tournamentName: String? = null,
)

@Serializable
data class SetScoreRequest(
    val team1Games: Int,
    val team2Games: Int,
    val tiebreakTeam1Points: Int? = null,
    val tiebreakTeam2Points: Int? = null,
)

/** Body for `POST /api/v1/matches/{id}/result` — upload the set scores. */
@Serializable
data class MatchResultRequest(
    val sets: List<SetScoreRequest>,
)

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
    val ratingSystem: String,
    val matchType: String,
    val matchFormat: String,
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
)

fun Match.toResponse(): MatchResponse =
    MatchResponse(
        id = id.toString(),
        ratingSystem = ratingSystem.name,
        matchType = matchType.name,
        matchFormat = matchFormat.name,
        matchDate = matchDate.toString(),
        status = status.name,
        team1 = MatchSideResponse(team1.teamId.toString(), team1.userIds.map { it.toString() }),
        team2 = MatchSideResponse(team2.teamId.toString(), team2.userIds.map { it.toString() }),
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
    )
