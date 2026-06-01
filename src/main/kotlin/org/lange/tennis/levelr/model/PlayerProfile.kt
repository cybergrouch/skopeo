package org.lange.tennis.levelr.model

import kotlinx.serialization.Serializable

@Serializable
data class PlayerProfile(
    val playerId: String,
    val name: String,
    val rating: Rating,
) {
    init {
        require(playerId.isNotBlank()) { "Player ID must not be blank" }
        require(playerId.length <= 50) { "Player ID must be at most 50 characters" }
        require(name.isNotBlank()) { "Player name must not be blank" }
        require(name.length <= 100) { "Player name must be at most 100 characters" }
    }
}
