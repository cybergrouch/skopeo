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
 * @property winnerTeamId Team ID of the match winner
 * @property matchFormat Format of the match (best of 3 or 5)
 * @property loserTeamId Team ID of the match loser; derived from the first set's
 *   winner/loser when omitted (the team that is not the match winner), so payloads
 *   may state it explicitly or leave it out
 */
@Serializable
data class MatchScore(
    val sets: List<SetScore>,
    val winnerTeamId: String = sets.maxOf { it.winnerTeamId },
    val matchFormat: MatchFormat = MatchFormat.BEST_OF_THREE,
    val loserTeamId: String = sets.first().let { if (it.winnerTeamId == winnerTeamId) it.loserTeamId else it.winnerTeamId },
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
