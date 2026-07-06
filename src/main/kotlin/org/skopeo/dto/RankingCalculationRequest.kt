// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto

import kotlinx.serialization.Serializable
import org.skopeo.model.MatchScore
import org.skopeo.model.RatingCalculationOptions
import org.skopeo.model.Team

@Serializable
data class RankingCalculationRequest(
    val teams: Map<String, Team>,
    val matchScore: MatchScore,
    val matchDate: String? = null,
    val options: RatingCalculationOptions? = null,
) {
    init {
        // Validation: Exactly 2 teams required
        require(value = teams.size == 2) {
            "Exactly 2 teams required for a match, got ${teams.size}"
        }

        // Validation: Map keys must match team IDs
        teams.forEach { (key, team) ->
            require(value = key == team.teamId) {
                "Team map key '$key' must match team ID '${team.teamId}'"
            }
        }

        // Validation: Match score must reference valid team IDs
        val teamIds = teams.keys
        matchScore.sets.forEach { set ->
            set.games.keys.forEach { id ->
                require(value = id in teamIds) {
                    "Set games reference invalid team ID '$id', valid IDs: $teamIds"
                }
            }
            require(value = set.winnerTeamId in teamIds) {
                "Set winner '${set.winnerTeamId}' is not a valid team ID, valid IDs: $teamIds"
            }
            set.tiebreak?.let { tb ->
                tb.points.keys.forEach { id ->
                    require(value = id in teamIds) {
                        "Tiebreak points reference invalid team ID '$id', valid IDs: $teamIds"
                    }
                }
                require(value = tb.winnerTeamId in teamIds) {
                    "Tiebreak winner '${tb.winnerTeamId}' is not a valid team ID, valid IDs: $teamIds"
                }
            }
        }

        // Both teams must be the same format (no singles-vs-doubles). Per-team player counts are already
        // enforced by Team.init (SINGLES = 1, DOUBLES/MIXED_DOUBLES = 2), so this only rules out a mismatch.
        require(value = teams.values.map { it.teamType }.toSet().size == 1) {
            "Both teams must have the same format, got ${teams.values.map { it.teamType }}"
        }
    }
}
