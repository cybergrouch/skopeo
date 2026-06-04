package org.lange.tennis.levelr.model

import kotlinx.serialization.Serializable
import org.lange.tennis.levelr.service.calculator.impl.bd
import java.math.BigDecimal

@Serializable
data class MatchScore(
    val sets: List<SetScore>,
    val winner: String = sets.maxOf { it.winner },
    val matchFormat: MatchFormat = MatchFormat.BEST_OF_THREE,
) {
    init {
        require(sets.isNotEmpty()) { "Match must have at least 1 set" }
        require(sets.size <= 5) { "Match cannot have more than 5 sets" }

        // Validate that all sets have the same players
        val playerIds = sets.first().games.keys
        require(sets.all { it.games.keys == playerIds }) {
            "All sets must have the same players"
        }
    }
}

internal fun MatchScore.totalGamesWon(playerId: String): Int = sets.sumOf { it.games[playerId] ?: 0 }

internal val MatchScore.loser: String get() = sets.first().games.keys.first { it != this.winner }

internal fun MatchScore.calculateDominanceFactor(playerId: String): BigDecimal =
    sets.flatMap { it.games.keys }.distinct().take(2).let { players ->
        val gamesWonByPlayer = this.totalGamesWon(playerId)
        val gamesWonByOpponent = players.filter { it != playerId }.firstNotNullOfOrNull { this.totalGamesWon(it) } ?: 0
        return (gamesWonByPlayer - gamesWonByOpponent).bd / (gamesWonByPlayer + gamesWonByOpponent).bd
    }

internal val MatchScore.matchScore: String get() =
    sets.map {
        val gamesWonByWinner = it.games[winner] ?: 0
        val gamesWonByLoser = it.games[loser] ?: 0
        "$gamesWonByWinner-$gamesWonByLoser"
    }.joinToString(" ")
