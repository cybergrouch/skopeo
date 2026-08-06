// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.match

import org.skopeo.common.dto.match.MatchPublicEvent
import org.skopeo.common.dto.match.MatchPublicHeadToHead
import org.skopeo.common.dto.match.MatchPublicPlayer
import org.skopeo.common.dto.match.MatchPublicRatingChange
import org.skopeo.common.dto.match.MatchPublicResponse
import org.skopeo.common.dto.match.MatchPublicSet
import org.skopeo.common.dto.match.MatchResponse
import org.skopeo.common.dto.match.MatchSetResponse
import org.skopeo.common.dto.match.MatchSideResponse
import org.skopeo.domain.model.Match
import java.util.UUID

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
        team1Handicap = team1Handicap?.toPlainString(),
        team2Handicap = team2Handicap?.toPlainString(),
    )

/** Build the public response, resolving each side's players via [players] (id → name/code). */
fun Match.toPublicResponse(
    players: Map<UUID, MatchPublicPlayer>,
    ratingChanges: List<MatchPublicRatingChange>? = null,
    headToHead: MatchPublicHeadToHead? = null,
    event: MatchPublicEvent? = null,
): MatchPublicResponse {
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
        rated = ratedAt != null,
        isActive = isActive,
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
        ratingChanges = ratingChanges,
        headToHead = headToHead,
        event = event,
        team1Handicap = team1Handicap?.toPlainString(),
        team2Handicap = team2Handicap?.toPlainString(),
    )
}
