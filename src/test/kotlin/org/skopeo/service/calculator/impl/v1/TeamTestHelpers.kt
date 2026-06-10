package org.skopeo.service.calculator.impl.v1

import org.skopeo.dto.RankingCalculationRequest
import org.skopeo.model.MatchScore
import org.skopeo.model.PlayerProfile
import org.skopeo.model.Rating
import org.skopeo.model.RatingCalculationOptions
import org.skopeo.model.RatingSystem
import org.skopeo.model.SetScore
import org.skopeo.model.Team
import org.skopeo.model.TeamType
import org.skopeo.model.TiebreakScore

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
