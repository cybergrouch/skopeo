package org.lange.tennis.levelr.model

import kotlinx.serialization.Serializable

@Serializable
data class MatchScore(
    val sets: List<SetScore>,
    val matchFormat: MatchFormat = MatchFormat.BEST_OF_THREE,
) {
    init {
        require(sets.isNotEmpty()) { "Match must have at least 1 set" }
        require(sets.size <= 5) { "Match cannot have more than 5 sets" }

        // Validate that all sets have the same players
        val playerIds = sets.first().games.keys
        require(sets.all { it.games.keys == playerIds }) {
            "All sets must have the same players"
        }
    }
}
