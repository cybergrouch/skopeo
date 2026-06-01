package org.lange.tennis.levelr.dto

import kotlinx.serialization.Serializable
import org.lange.tennis.levelr.model.MatchScore
import org.lange.tennis.levelr.model.PlayerProfile

@Serializable
data class RankingCalculationRequest(
    val players: Map<String, PlayerProfile>,
    val matchScore: MatchScore,
    val matchDate: String? = null,
) {
    init {
        require(players.size == 2) { "Exactly 2 players required for singles match, got ${players.size}" }

        // Validate that map keys match player IDs
        require(players.all { (key, profile) -> key == profile.playerId }) {
            "Map key must match player profile ID"
        }

        // Validate that both players use the same rating system
        val ratingSystems = players.values.map { it.rating.system }.toSet()
        require(ratingSystems.size == 1) {
            "Both players must use the same rating system, got ${ratingSystems.joinToString()}"
        }

        // Validate that set scores reference valid player IDs
        matchScore.sets.forEach { set ->
            require(set.games.keys.all { it in players.keys }) {
                "Set score contains invalid player ID"
            }
            require(set.winner in players.keys) {
                "Set winner '${set.winner}' is not a valid player ID"
            }
        }
    }
}
