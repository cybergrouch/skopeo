// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import kotlinx.serialization.Serializable
import org.skopeo.service.calculator.impl.bd
import org.skopeo.service.calculator.impl.divideBy
import java.math.BigDecimal

/**
 * Represents the score of a tennis match.
 *
 * Winner and loser are team IDs (resolved further to player IDs once ratings are
 * kept in a database).
 *
 * @property sets List of set scores
 * @property winnerTeamId Team ID of the match winner; defaults to the team that won
 *   the most sets when omitted (tennis has no draws, so a valid match always has a
 *   strict set majority)
 * @property loserTeamId Team ID of the match loser; defaults to the team that lost
 *   the most sets when omitted, so payloads may state both explicitly or leave them out.
 *   A stated winner that contradicts the set majority therefore collides with the
 *   derived loser and is rejected
 * @property matchFormat Format of the match (best of 3 or 5)
 */
@Serializable
data class MatchScore(
    val sets: List<SetScore>,
    val winnerTeamId: String = sets.groupingBy { it.winnerTeamId }.eachCount().maxBy { it.value }.key,
    val loserTeamId: String = sets.groupingBy { it.loserTeamId }.eachCount().maxBy { it.value }.key,
    val matchFormat: MatchFormat = MatchFormat.BEST_OF_THREE,
) {
    init {
        require(sets.isNotEmpty()) { "Match must have at least 1 set" }
        require(sets.size <= 5) { "Match cannot have more than 5 sets" }

        // Validate that all sets have the same teams
        val teamIds = sets.first().games.keys
        require(sets.all { it.games.keys == teamIds }) {
            "All sets must have the same teams"
        }

        require(winnerTeamId in teamIds) { "Match winner '$winnerTeamId' must be one of the teams: $teamIds" }
        require(loserTeamId in teamIds) { "Match loser '$loserTeamId' must be one of the teams: $teamIds" }
        require(loserTeamId != winnerTeamId) { "Match loser '$loserTeamId' must differ from the winner '$winnerTeamId'" }
    }
}

/**
 * Match dominance is the average of per-set dominance, where each set's dominance
 * is the efficiency formula (gamesWon − gamesLost) / (gamesWon + gamesLost).
 *
 * Games are deliberately NOT pooled across sets: a game's weight depends on whether
 * its set was won or lost, so pooling would let games from different sets offset
 * each other incorrectly. Averaging keeps the single-set base case identical
 * (one set's dominance is just that set's efficiency).
 */
internal fun MatchScore.calculateDominanceFactor(teamId: String): BigDecimal =
    sets.sumOf { set ->
        val gamesWon = set.games[teamId] ?: 0
        val gamesLost = set.games.filterKeys { it != teamId }.values.sum()
        (gamesWon - gamesLost).bd.divideBy(divisor = (gamesWon + gamesLost).bd)
    }.divideBy(divisor = sets.size.bd)

internal val MatchScore.matchScore: String get() =
    sets.map {
        val gamesWonByWinner = it.games[winnerTeamId] ?: 0
        val gamesWonByLoser = it.games[loserTeamId] ?: 0
        "$gamesWonByWinner-$gamesWonByLoser"
    }.joinToString(" ")
