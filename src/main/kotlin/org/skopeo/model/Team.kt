package org.skopeo.model

import kotlinx.serialization.Serializable

/**
 * Represents a team in a match.
 *
 * For singles matches, the team contains exactly one player.
 * For doubles matches (future), the team contains exactly two players.
 *
 * @property teamId Unique identifier for this team in the match context
 * @property name Team name (for singles: player name, for doubles: "Player1/Player2")
 * @property players List of players in this team (size 1 for singles, 2 for doubles)
 * @property teamType Type of team (SINGLES, DOUBLES, MIXED_DOUBLES)
 */
@Serializable
data class Team(
    val teamId: String,
    val name: String,
    val players: List<PlayerProfile>,
    val teamType: TeamType = TeamType.SINGLES,
) {
    init {
        // Validation
        require(teamId.isNotBlank() && teamId.length in 1..50) {
            "Team ID must be 1-50 characters, got '$teamId'"
        }
        require(name.isNotBlank() && name.length in 1..100) {
            "Team name must be 1-100 characters, got '$name'"
        }
        require(players.isNotEmpty()) {
            "Team must have at least one player"
        }

        // Team type validation
        when (teamType) {
            TeamType.SINGLES -> {
                require(players.size == 1) {
                    "SINGLES team must have exactly 1 player, got ${players.size}"
                }
            }
            TeamType.DOUBLES, TeamType.MIXED_DOUBLES -> {
                require(players.size == 2) {
                    "DOUBLES/MIXED_DOUBLES team must have exactly 2 players, got ${players.size}"
                }
            }
        }

        // All players must use the same rating system
        val ratingSystems = players.map { it.rating.system }.distinct()
        require(ratingSystems.size == 1) {
            "All players in a team must use the same rating system, got: $ratingSystems"
        }
    }

    /**
     * For singles teams, returns the single player.
     * Throws for doubles teams (not yet supported).
     */
    fun getSinglePlayer(): PlayerProfile {
        require(teamType == TeamType.SINGLES && players.size == 1) {
            "getSinglePlayer() only works for SINGLES teams with 1 player"
        }
        return players[0]
    }

    /**
     * Returns the rating system used by this team's players.
     */
    val ratingSystem: RatingSystem
        get() = players[0].rating.system
}

/**
 * Type of team in a match.
 */
@Serializable
enum class TeamType {
    /** Singles match: 1 player per team */
    SINGLES,

    /** Doubles match: 2 players per team */
    DOUBLES,

    /** Mixed doubles: 2 players per team (male + female) */
    MIXED_DOUBLES,
}
