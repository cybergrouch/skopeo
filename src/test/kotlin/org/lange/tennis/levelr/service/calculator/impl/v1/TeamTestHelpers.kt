package org.lange.tennis.levelr.service.calculator.impl.v1

import org.lange.tennis.levelr.dto.RankingCalculationRequest
import org.lange.tennis.levelr.model.MatchScore
import org.lange.tennis.levelr.model.PlayerProfile
import org.lange.tennis.levelr.model.Rating
import org.lange.tennis.levelr.model.RatingCalculationOptions
import org.lange.tennis.levelr.model.RatingSystem
import org.lange.tennis.levelr.model.SetScore
import org.lange.tennis.levelr.model.Team
import org.lange.tennis.levelr.model.TeamType

/**
 * Helper function to create a singles match request with team-based structure.
 *
 * For singles matches, each team has exactly one player.
 */
fun createSinglesRequest(
    p1Rating: String,
    p2Rating: String,
    system: RatingSystem,
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
            rating = Rating.fromValue(value = p1Rating, system = system),
        )

    val player2 =
        PlayerProfile(
            playerId = "P2",
            name = "Player 2",
            rating = Rating.fromValue(value = p2Rating, system = system),
        )

    val team1 =
        Team(
            teamId = "T1",
            name = player1.name,
            players = listOf(player1),
            teamType = TeamType.SINGLES,
        )

    val team2 =
        Team(
            teamId = "T2",
            name = player2.name,
            players = listOf(player2),
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
                        SetScore(
                            games = mapOf("T1" to p1Games, "T2" to p2Games),
                            winner = winner,
                        ),
                    ),
            ),
        options = options,
    )
}
