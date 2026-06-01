package org.lange.tennis.levelr.model

import kotlinx.serialization.Serializable

@Serializable
data class TiebreakScore(
    val points: Map<String, Int>,
    val winner: String,
) {
    init {
        require(points.size == 2) { "Tiebreak must have exactly 2 players" }
        require(winner in points.keys) { "Winner must be one of the players in the tiebreak" }

        val winnerPoints = points[winner] ?: 0
        val loserPoints = points.values.first { it != winnerPoints }

        require(winnerPoints >= 7) { "Tiebreak winner must have at least 7 points" }
        require(winnerPoints - loserPoints >= 2) { "Tiebreak must be won by at least 2 points" }
    }
}
