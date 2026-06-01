package org.lange.tennis.levelr.service

import org.lange.tennis.levelr.dto.RankingCalculationRequest
import org.lange.tennis.levelr.dto.RankingCalculationResponse
import org.lange.tennis.levelr.dto.RatingChange
import org.lange.tennis.levelr.model.PlayerProfile
import org.lange.tennis.levelr.model.Rating
import org.lange.tennis.levelr.model.RatingSystem
import kotlin.math.pow
import kotlin.math.round

/**
 * Service for calculating tennis ranking updates based on match results.
 * Uses an ELO-based algorithm adapted for tennis rating systems (NTRP and UTR).
 *
 * This is a pure function with no side effects - it returns both the calculation
 * result and an audit trail that can be logged by the caller.
 */
class RankingCalculator {
    companion object {
        // K-factor controls how much ratings change per match
        private const val K_FACTOR = 32.0

        // Scale factor for expected score calculation
        private const val SCALE_FACTOR = 400.0

        // NTRP-specific constants
        private const val NTRP_MIN = 1.0
        private const val NTRP_MAX = 7.0

        // UTR-specific constants
        private const val UTR_MIN = 1.0
    }

    /**
     * Calculate updated rankings based on match results.
     * Returns both the calculation result and an audit trail.
     */
    fun calculate(request: RankingCalculationRequest): RankingCalculationResult {
        val audit = AuditTrail()

        val playerIds = request.players.keys.toList()
        val player1Id = playerIds[0]
        val player2Id = playerIds[1]

        val player1 = request.players[player1Id]!!
        val player2 = request.players[player2Id]!!

        audit.add(
            AuditEntry(
                message =
                    "Calculating ranking for ${player1.name} (${player1.rating.value}) vs " +
                        "${player2.name} (${player2.rating.value})",
                context =
                    mapOf(
                        "player1" to player1.name,
                        "player1Rating" to player1.rating.value,
                        "player2" to player2.name,
                        "player2Rating" to player2.rating.value,
                    ),
            ),
        )

        // Determine match winner and calculate performance score
        val matchResult =
            analyzeMatchResult(
                request = request,
                player1Id = player1Id,
                player2Id = player2Id,
            )

        audit.add(
            AuditEntry(
                message =
                    "Match result - Winner: ${matchResult.winnerId}, " +
                        "Score: ${matchResult.winnerScore}, Sets: ${matchResult.setsWon}",
                context =
                    mapOf(
                        "winnerId" to matchResult.winnerId,
                        "winnerScore" to matchResult.winnerScore,
                        "setsWon" to matchResult.setsWon,
                        "dominanceFactor" to matchResult.dominanceFactor,
                    ),
            ),
        )

        // Calculate ELO-based rating changes
        val (player1Change, player2Change) =
            calculateRatingChanges(
                player1 = player1,
                player2 = player2,
                player1ActualScore = matchResult.player1Score,
                player2ActualScore = matchResult.player2Score,
                dominanceFactor = matchResult.dominanceFactor,
                audit = audit,
            )

        audit.add(
            AuditEntry(
                message = "Rating changes - ${player1.name}: $player1Change, ${player2.name}: $player2Change",
                context =
                    mapOf(
                        "player1Change" to player1Change,
                        "player2Change" to player2Change,
                    ),
            ),
        )

        // Apply rating changes with system-specific constraints
        val player1NewRating = applyRatingChange(rating = player1.rating, change = player1Change, audit = audit)
        val player2NewRating = applyRatingChange(rating = player2.rating, change = player2Change, audit = audit)

        val updatedPlayers =
            mapOf(
                player1Id to player1.copy(rating = player1NewRating),
                player2Id to player2.copy(rating = player2NewRating),
            )

        val ratingChanges =
            mapOf(
                player1Id to
                    RatingChange(
                        change = player1NewRating.value - player1.rating.value,
                        percentChange = ((player1NewRating.value - player1.rating.value) / player1.rating.value) * 100,
                        previousRating = player1.rating,
                        newRating = player1NewRating,
                    ),
                player2Id to
                    RatingChange(
                        change = player2NewRating.value - player2.rating.value,
                        percentChange = ((player2NewRating.value - player2.rating.value) / player2.rating.value) * 100,
                        previousRating = player2.rating,
                        newRating = player2NewRating,
                    ),
            )

        val response =
            RankingCalculationResponse(
                players = updatedPlayers,
                ratingChanges = ratingChanges,
            )

        return RankingCalculationResult(
            response = response,
            audit = audit.getEntries(),
        )
    }

    /**
     * Analyze match result to determine winner and calculate dominance factor.
     */
    private fun analyzeMatchResult(
        request: RankingCalculationRequest,
        player1Id: String,
        player2Id: String,
    ): MatchResult {
        val sets = request.matchScore.sets
        val setsWonByPlayer1 = sets.count { it.winner == player1Id }
        val setsWonByPlayer2 = sets.count { it.winner == player2Id }

        val winnerId = if (setsWonByPlayer1 > setsWonByPlayer2) player1Id else player2Id
        val loserId = if (winnerId == player1Id) player2Id else player1Id

        // Calculate total games won for dominance factor
        var totalGamesWinner = 0
        var totalGamesLoser = 0

        for (set in sets) {
            val winnerGames = set.games[winnerId] ?: 0
            val loserGames = set.games[loserId] ?: 0
            totalGamesWinner += winnerGames
            totalGamesLoser += loserGames
        }

        // Dominance factor: ratio of games won (affects rating change magnitude)
        val dominanceFactor =
            if (totalGamesLoser > 0) {
                totalGamesWinner.toDouble() / totalGamesLoser.toDouble()
            } else {
                2.0 // Default for complete dominance
            }

        // Normalize dominance factor (1.0 = close match, 2.0+ = dominant win)
        val normalizedDominance = minOf(dominanceFactor, 2.5)

        return MatchResult(
            winnerId = winnerId,
            winnerScore = 1.0,
            player1Score = if (winnerId == player1Id) 1.0 else 0.0,
            player2Score = if (winnerId == player2Id) 1.0 else 0.0,
            setsWon = maxOf(setsWonByPlayer1, setsWonByPlayer2),
            dominanceFactor = normalizedDominance,
        )
    }

    /**
     * Calculate rating changes using ELO algorithm.
     */
    private fun calculateRatingChanges(
        player1: PlayerProfile,
        player2: PlayerProfile,
        player1ActualScore: Double,
        player2ActualScore: Double,
        dominanceFactor: Double,
        audit: AuditTrail,
    ): Pair<Double, Double> {
        // Calculate expected scores based on rating differential
        val expectedPlayer1 = calculateExpectedScore(ratingA = player1.rating.value, ratingB = player2.rating.value)
        val expectedPlayer2 = 1.0 - expectedPlayer1

        audit.add(
            AuditEntry(
                message = "Expected scores - Player1: $expectedPlayer1, Player2: $expectedPlayer2",
                context =
                    mapOf(
                        "expectedPlayer1" to expectedPlayer1,
                        "expectedPlayer2" to expectedPlayer2,
                    ),
            ),
        )

        // Calculate base rating changes
        val baseChange1 = K_FACTOR * (player1ActualScore - expectedPlayer1)
        val baseChange2 = K_FACTOR * (player2ActualScore - expectedPlayer2)

        // Apply dominance factor (larger changes for more decisive matches)
        val adjustedChange1 = baseChange1 * dominanceFactor
        val adjustedChange2 = baseChange2 * dominanceFactor

        return Pair(adjustedChange1, adjustedChange2)
    }

    /**
     * Calculate expected score based on rating differential (ELO formula).
     */
    private fun calculateExpectedScore(
        ratingA: Double,
        ratingB: Double,
    ): Double {
        return 1.0 / (1.0 + 10.0.pow((ratingB - ratingA) / SCALE_FACTOR))
    }

    /**
     * Apply rating change with system-specific constraints.
     */
    private fun applyRatingChange(
        rating: Rating,
        change: Double,
        audit: AuditTrail,
    ): Rating {
        return when (rating.system) {
            RatingSystem.NTRP -> applyNTRPChange(rating = rating, change = change, audit = audit)
            RatingSystem.UTR -> applyUTRChange(rating = rating, change = change, audit = audit)
        }
    }

    /**
     * Apply rating change for NTRP (continuous values, range 1.0-7.0).
     */
    private fun applyNTRPChange(
        rating: Rating,
        change: Double,
        audit: AuditTrail,
    ): Rating {
        val newValue = rating.value + change

        // Round to 2 decimal places for NTRP
        val rounded = round(newValue * 100.0) / 100.0

        // Clamp to valid NTRP range
        val clamped = rounded.coerceIn(NTRP_MIN, NTRP_MAX)

        audit.add(
            AuditEntry(
                message = "NTRP change: ${rating.value} + $change = $newValue -> rounded $rounded -> clamped $clamped",
                context =
                    mapOf(
                        "system" to "NTRP",
                        "original" to rating.value,
                        "change" to change,
                        "newValue" to newValue,
                        "rounded" to rounded,
                        "clamped" to clamped,
                    ),
            ),
        )

        return Rating(value = clamped, system = RatingSystem.NTRP)
    }

    /**
     * Apply rating change for UTR (decimal values allowed, minimum 1.0).
     */
    private fun applyUTRChange(
        rating: Rating,
        change: Double,
        audit: AuditTrail,
    ): Rating {
        val newValue = rating.value + change

        // Round to 1 decimal place for UTR
        val rounded = round(newValue * 10.0) / 10.0

        // Ensure minimum UTR rating
        val clamped = maxOf(rounded, UTR_MIN)

        audit.add(
            AuditEntry(
                message = "UTR change: ${rating.value} + $change = $newValue -> rounded $rounded -> clamped $clamped",
                context =
                    mapOf(
                        "system" to "UTR",
                        "original" to rating.value,
                        "change" to change,
                        "newValue" to newValue,
                        "rounded" to rounded,
                        "clamped" to clamped,
                    ),
            ),
        )

        return Rating(value = clamped, system = RatingSystem.UTR)
    }

    private data class MatchResult(
        val winnerId: String,
        val winnerScore: Double,
        val player1Score: Double,
        val player2Score: Double,
        val setsWon: Int,
        val dominanceFactor: Double,
    )
}
