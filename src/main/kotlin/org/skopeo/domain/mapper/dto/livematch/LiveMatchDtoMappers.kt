// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.livematch

import org.skopeo.common.dto.livematch.LiveMatchResponse
import org.skopeo.common.dto.livematch.LiveOutcomeResponse
import org.skopeo.common.dto.livematch.LivePlayerResponse
import org.skopeo.common.dto.livematch.LiveSetResponse
import org.skopeo.domain.model.CompletedSet
import org.skopeo.domain.model.LiveMatchView
import org.skopeo.domain.model.LiveOutcome
import org.skopeo.domain.model.MatchTiming
import org.skopeo.domain.model.TeamSide

/**
 * The live score as the wire sees it (#911).
 *
 * Points are **rendered here**, not on the client: `displayPoints` is the one implementation of deuce and
 * advantage, and handing out raw counts would invite every client to grow a second one.
 */
fun LiveMatchView.toResponse(
    timing: MatchTiming,
    players: List<LivePlayerResponse> = emptyList(),
): LiveMatchResponse =
    LiveMatchResponse(
        matchId = matchId.toString(),
        sequence = sequence,
        scorerId = scorerId?.toString(),
        hasStarted = state.hasStarted,
        isBetweenSets = state.isBetweenSets,
        isPaused = state.isPaused,
        isTiebreak = state.isTiebreak,
        serverId = state.serverId?.toString(),
        serverName = state.serverId?.let { id -> players.firstOrNull { it.userId == id.toString() }?.name },
        players = players,
        pointsTeam1 = state.displayPoints(side = TeamSide.TEAM1),
        pointsTeam2 = state.displayPoints(side = TeamSide.TEAM2),
        gamesTeam1 = state.gamesTeam1,
        gamesTeam2 = state.gamesTeam2,
        sets = state.completedSets.map { it.toResponse() },
        elapsedSeconds = timing.elapsedSeconds,
        isRunning = timing.isRunning,
        outcome = state.outcome?.toResponse(),
    )

private fun CompletedSet.toResponse(): LiveSetResponse =
    LiveSetResponse(
        gamesTeam1 = gamesTeam1,
        gamesTeam2 = gamesTeam2,
        winner = winner.name,
        tiebreakTeam1Points = tiebreakTeam1Points,
        tiebreakTeam2Points = tiebreakTeam2Points,
    )

private fun LiveOutcome.toResponse(): LiveOutcomeResponse =
    LiveOutcomeResponse(kind = kind.name, winner = winner.name, concededBy = concededBy?.name)
