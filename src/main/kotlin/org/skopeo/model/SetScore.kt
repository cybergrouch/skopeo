package org.skopeo.model

import kotlinx.serialization.Serializable

/**
 * Represents the score of a set in a tennis match.
 *
 * @property games Map of team ID to games won in this set
 * @property winner Team ID of the set winner
 * @property tiebreak Optional tiebreak score if the set went to a tiebreak
 */
@Serializable
data class SetScore(
    val games: Map<String, Int>,
    val winner: String,
    val tiebreak: TiebreakScore? = null,
) {
    init {
        require(games.size == 2) { "Set must have exactly 2 teams" }
        require(winner in games.keys) { "Winner must be one of the teams in the set" }

        val winnerGames = games[winner] ?: 0
        val loserGames = games.values.first { it != winnerGames }

        require(winnerGames >= 0 && loserGames >= 0) { "Game scores must be non-negative" }

        // Validate set scores
        if (tiebreak != null) {
            // Tiebreak set (usually 7-6 or 6-7)
            require((winnerGames == 7 && loserGames == 6) || (winnerGames == 6 && loserGames == 7)) {
                "Tiebreak set must be 7-6 or 6-7, got $winnerGames-$loserGames"
            }
        } else {
            // Regular set - must win by 2 games, or 6-4, 6-3, etc.
            require(winnerGames >= 6) { "Winner must have at least 6 games in a regular set" }
            require(winnerGames - loserGames >= 2) { "Set must be won by at least 2 games" }
            require(winnerGames <= 7) { "Games should not exceed 7 without tiebreak" }
        }
    }
}
