// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.calculator.impl.v2

import org.skopeo.domain.model.MatchScore
import org.skopeo.domain.model.PlayerProfile
import org.skopeo.domain.model.Rating
import org.skopeo.domain.model.RatingCalculationOptions
import org.skopeo.domain.model.SetScore
import org.skopeo.domain.model.Team
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.TiebreakScore
import org.skopeo.dto.RankingCalculationRequest

/**
 * Helper function to create a singles match request with team-based structure.
 *
 * For singles matches, each team has exactly one player.
 */
@Suppress("LongParameterList") // a test-request builder; the score/smoothing knobs are clearer as flat params
fun createSinglesRequest(
    p1Rating: String,
    p2Rating: String,
    p1Games: Int,
    p2Games: Int,
    // "T1" or "T2"
    winner: String,
    smoothingEnabled: Boolean = false,
    smoothingFactor: Double = 0.5,
): RankingCalculationRequest {
    val player1 =
        PlayerProfile(
            playerId = "P1",
            name = "Player 1",
            rating = Rating.fromValue(value = p1Rating),
        )

    val player2 =
        PlayerProfile(
            playerId = "P2",
            name = "Player 2",
            rating = Rating.fromValue(value = p2Rating),
        )

    val team1 =
        Team(
            teamId = "T1",
            name = player1.name,
            players = listOf(element = player1),
            teamType = TeamType.SINGLES,
        )

    val team2 =
        Team(
            teamId = "T2",
            name = player2.name,
            players = listOf(element = player2),
            teamType = TeamType.SINGLES,
        )

    val options =
        if (smoothingEnabled) {
            RatingCalculationOptions(
                smoothingEnabled = true,
                smoothingFactor = smoothingFactor,
            )
        } else {
            null
        }

    return RankingCalculationRequest(
        teams =
            mapOf(
                "T1" to team1,
                "T2" to team2,
            ),
        matchScore =
            MatchScore(
                sets =
                    listOf(
                        element =
                            SetScore(
                                games = mapOf("T1" to p1Games, "T2" to p2Games),
                                winnerTeamId = winner,
                                tiebreak = tiebreakFor(p1Games = p1Games, p2Games = p2Games, winner = winner),
                            ),
                    ),
            ),
        options = options,
    )
}

/**
 * 7-6 sets are only legal with a tiebreak; build an informational one (7-5 points)
 * won by the set winner. Returns null for all other scores.
 */
private fun tiebreakFor(
    p1Games: Int,
    p2Games: Int,
    winner: String,
): TiebreakScore? {
    if (setOf(p1Games, p2Games) != setOf(7, 6)) {
        return null
    }
    val winnerPoints = 7
    val loserPoints = 5
    return TiebreakScore(
        points =
            mapOf(
                "T1" to (if (p1Games > p2Games) winnerPoints else loserPoints),
                "T2" to (if (p2Games > p1Games) winnerPoints else loserPoints),
            ),
        winnerTeamId = winner,
    )
}
