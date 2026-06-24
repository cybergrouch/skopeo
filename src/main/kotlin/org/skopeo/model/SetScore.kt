// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import kotlinx.serialization.Serializable

/**
 * Represents the score of a set in a tennis match.
 *
 * Winner and loser are team IDs (resolved further to player IDs once ratings are
 * kept in a database).
 *
 * @property games Map of team ID to games won in this set
 * @property winnerTeamId Team ID of the set winner; defaults to the team with the most
 *   games when omitted
 * @property loserTeamId Team ID of the set loser; defaults to the team with the fewest
 *   games when omitted, so payloads may state both explicitly or leave them out
 * @property tiebreak Optional tiebreak score if the set went to a tiebreak.
 *   The tiebreak is part of the set score and therefore must be won by the set winner.
 *   The object is informational only — it documents how many points the tiebreak was
 *   (e.g. 7-5); tiebreak points are NOT included in the dominance calculation of the
 *   match, which counts games only (a 7-6 set contributes 7 vs 6 games regardless of
 *   the tiebreak points).
 */
@Serializable
data class SetScore(
    val games: Map<String, Int>,
    val winnerTeamId: String = games.maxBy { it.value }.key,
    val loserTeamId: String = games.minBy { it.value }.key,
    val tiebreak: TiebreakScore? = null,
) {
    init {
        require(value = games.size == 2) { "Set must have exactly 2 teams" }
        require(value = winnerTeamId in games.keys) { "Winner must be one of the teams in the set" }
        require(value = loserTeamId in games.keys) { "Set loser '$loserTeamId' must be one of the teams in the set" }
        require(value = loserTeamId != winnerTeamId) { "Set loser '$loserTeamId' must differ from the set winner '$winnerTeamId'" }

        val winnerGames = games[winnerTeamId] ?: 0
        val loserGames = games[loserTeamId] ?: 0

        require(value = winnerGames >= 0 && loserGames >= 0) { "Game scores must be non-negative" }

        // Validate set scores
        if (tiebreak != null) {
            // The tiebreak decides the set, so its winner must be the set winner
            require(value = tiebreak.winnerTeamId == winnerTeamId) {
                "Tiebreak winner '${tiebreak.winnerTeamId}' must be the set winner '$winnerTeamId'"
            }

            // Tiebreak set (usually 7-6 or 6-7)
            require(value = (winnerGames == 7 && loserGames == 6) || (winnerGames == 6 && loserGames == 7)) {
                "Tiebreak set must be 7-6 or 6-7, got $winnerGames-$loserGames"
            }
        } else {
            // Regular set - must win by 2 games, or 6-4, 6-3, etc.
            require(value = winnerGames >= 6) { "Winner must have at least 6 games in a regular set" }
            require(value = winnerGames - loserGames >= 2) { "Set must be won by at least 2 games" }
            require(value = winnerGames <= 7) { "Games should not exceed 7 without tiebreak" }
        }
    }
}
