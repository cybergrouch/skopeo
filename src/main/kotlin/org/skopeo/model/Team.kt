// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
 * @property handicap Optional per-side rating handicap in team-mean (player-level NTRP) units (issue #486).
 *   When set, it is *deducted* from this side's rating for the rating-delta computation only (widening the
 *   perceived gap); the resulting delta is applied to the players' true ratings. Range `0 < h <= 1.0`.
 *   Serialized as a string to preserve money-style precision; `null` = no handicap. See RATING_HANDICAP.md.
 */
@Serializable
data class Team(
    val teamId: String,
    val name: String,
    val players: List<PlayerProfile>,
    val teamType: TeamType = TeamType.SINGLES,
    // Omitted from serialized output when null so the response contract stays free of null-valued
    // fields (the exact-payload contract has no nulls); only appears when a handicap is actually set.
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val handicap: String? = null,
) {
    init {
        // Validation
        require(value = teamId.isNotBlank() && teamId.length in 1..50) {
            "Team ID must be 1-50 characters, got '$teamId'"
        }
        require(value = name.isNotBlank() && name.length in 1..100) {
            "Team name must be 1-100 characters, got '$name'"
        }
        require(value = players.isNotEmpty()) {
            "Team must have at least one player"
        }

        // Handicap (issue #486): optional per-side deduction in NTRP points, must be 0 < h <= 1.0.
        handicap?.let { raw ->
            val h = requireNotNull(value = raw.toDoubleOrNull()) { "Handicap must be a valid number, got '$raw'" }
            require(value = h > 0.0 && h <= 1.0) {
                "Handicap must be in the range 0 < h <= 1.0, got $raw"
            }
        }

        // Team type validation
        when (teamType) {
            TeamType.SINGLES -> {
                require(value = players.size == 1) {
                    "SINGLES team must have exactly 1 player, got ${players.size}"
                }
            }
            TeamType.DOUBLES, TeamType.MIXED_DOUBLES -> {
                require(value = players.size == 2) {
                    "DOUBLES/MIXED_DOUBLES team must have exactly 2 players, got ${players.size}"
                }
            }
        }
    }

    /**
     * For singles teams, returns the single player.
     * Throws for doubles teams (not yet supported).
     */
    fun getSinglePlayer(): PlayerProfile {
        require(value = teamType == TeamType.SINGLES && players.size == 1) {
            "getSinglePlayer() only works for SINGLES teams with 1 player"
        }
        return players[0]
    }
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
