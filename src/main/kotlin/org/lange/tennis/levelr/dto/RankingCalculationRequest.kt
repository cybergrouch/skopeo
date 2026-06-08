package org.lange.tennis.levelr.dto

import kotlinx.serialization.Serializable
import org.lange.tennis.levelr.model.MatchScore
import org.lange.tennis.levelr.model.RatingCalculationOptions
import org.lange.tennis.levelr.model.Team
import org.lange.tennis.levelr.model.TeamType

@Serializable
data class RankingCalculationRequest(
    val teams: Map<String, Team>,
    val matchScore: MatchScore,
    val matchDate: String? = null,
    val options: RatingCalculationOptions? = null,
) {
    init {
        // Validation: Exactly 2 teams required
        require(teams.size == 2) {
            "Exactly 2 teams required for a match, got ${teams.size}"
        }

        // Validation: Map keys must match team IDs
        teams.forEach { (key, team) ->
            require(key == team.teamId) {
                "Team map key '$key' must match team ID '${team.teamId}'"
            }
        }

        // Validation: Both teams must use the same rating system
        val ratingSystems = teams.values.map { it.ratingSystem }.distinct()
        require(ratingSystems.size == 1) {
            "Both teams must use the same rating system, got: $ratingSystems"
        }

        // Validation: Match score must reference valid team IDs
        val teamIds = teams.keys
        matchScore.sets.forEach { set ->
            set.games.keys.forEach { id ->
                require(id in teamIds) {
                    "Set games reference invalid team ID '$id', valid IDs: $teamIds"
                }
            }
            require(set.winner in teamIds) {
                "Set winner '${set.winner}' is not a valid team ID, valid IDs: $teamIds"
            }
            set.tiebreak?.let { tb ->
                tb.points.keys.forEach { id ->
                    require(id in teamIds) {
                        "Tiebreak points reference invalid team ID '$id', valid IDs: $teamIds"
                    }
                }
                require(tb.winner in teamIds) {
                    "Tiebreak winner '${tb.winner}' is not a valid team ID, valid IDs: $teamIds"
                }
            }
        }

        // For singles (current scope): verify both teams have exactly 1 player
        teams.values.forEach { team ->
            require(team.teamType == TeamType.SINGLES && team.players.size == 1) {
                "Only SINGLES matches are currently supported (1 player per team). " +
                    "Team '${team.teamId}' has ${team.players.size} players with type ${team.teamType}"
            }
        }
    }
}
