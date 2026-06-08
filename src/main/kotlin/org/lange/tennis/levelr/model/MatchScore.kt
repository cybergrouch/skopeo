package org.lange.tennis.levelr.model

import kotlinx.serialization.Serializable
import org.lange.tennis.levelr.service.calculator.impl.bd
import java.math.BigDecimal

/**
 * Represents the score of a tennis match.
 *
 * @property sets List of set scores
 * @property winner Team ID of the match winner
 * @property matchFormat Format of the match (best of 3 or 5)
 */
@Serializable
data class MatchScore(
    val sets: List<SetScore>,
    val winner: String = sets.maxOf { it.winner },
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
    }
}

internal fun MatchScore.totalGamesWon(teamId: String): Int = sets.sumOf { it.games[teamId] ?: 0 }

internal val MatchScore.loser: String get() = sets.first().games.keys.first { it != this.winner }

internal fun MatchScore.calculateDominanceFactor(teamId: String): BigDecimal =
    sets.flatMap { it.games.keys }.distinct().take(2).let { teams ->
        val gamesWonByTeam = this.totalGamesWon(teamId)
        val gamesWonByOpponent = teams.filter { it != teamId }.firstNotNullOfOrNull { this.totalGamesWon(it) } ?: 0
        return (gamesWonByTeam - gamesWonByOpponent).bd / (gamesWonByTeam + gamesWonByOpponent).bd
    }

internal val MatchScore.matchScore: String get() =
    sets.map {
        val gamesWonByWinner = it.games[winner] ?: 0
        val gamesWonByLoser = it.games[loser] ?: 0
        "$gamesWonByWinner-$gamesWonByLoser"
    }.joinToString(" ")
