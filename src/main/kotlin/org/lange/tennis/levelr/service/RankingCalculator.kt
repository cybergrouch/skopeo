package org.lange.tennis.levelr.service

import org.lange.tennis.levelr.dto.RankingCalculationRequest
import org.lange.tennis.levelr.dto.RankingCalculationResponse
import org.lange.tennis.levelr.dto.RatingChange
import org.lange.tennis.levelr.model.PlayerProfile
import org.lange.tennis.levelr.model.Rating
import org.lange.tennis.levelr.model.RatingSystem
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.pow

/**
 * Service for calculating tennis ranking updates based on match results.
 * Uses an Elo-based algorithm adapted for tennis rating systems (NTRP and UTR).
 *
 * This is a pure function with no side effects - it returns both the calculation
 * result and an audit trail that can be logged by the caller.
 *
 * All internal calculations use BigDecimal with 6 decimal places precision
 * to ensure accurate and predictable results.
 */
class RankingCalculator {
    companion object {
        // Precision for all calculations (6 decimal places)
        private const val CALCULATION_SCALE = 6
        private val ROUNDING_MODE = RoundingMode.HALF_UP

        // K-factor controls how much ratings change per match
        private val K_FACTOR = BigDecimal("32.0")

        // Scale factor for expected score calculation
        private val SCALE_FACTOR = BigDecimal("400.0")

        // NTRP-specific constants
        private val NTRP_MIN = BigDecimal("1.0")
        private val NTRP_MAX = BigDecimal("7.0")

        // UTR-specific constants
        private val UTR_MIN = BigDecimal("1.0")

        // Helper extension functions for BigDecimal conversions
        private fun Double.toBigDecimalPrecise(): BigDecimal = BigDecimal(this.toString()).setScale(CALCULATION_SCALE, ROUNDING_MODE)

        private fun Int.toBigDecimalPrecise(): BigDecimal = BigDecimal(this).setScale(CALCULATION_SCALE, ROUNDING_MODE)

        private fun BigDecimal.toDoublePrecise(): Double = this.toDouble()
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
                        "Score: ${matchResult.winnerScore.toDoublePrecise()}, Sets: ${matchResult.setsWon}",
                context =
                    mapOf(
                        "winnerId" to matchResult.winnerId,
                        "winnerScore" to matchResult.winnerScore.toDoublePrecise(),
                        "setsWon" to matchResult.setsWon,
                        "dominanceFactor" to matchResult.dominanceFactor.toDoublePrecise(),
                    ),
            ),
        )

        // Calculate Elo-based rating changes
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
                message =
                    "Rating changes - ${player1.name}: ${player1Change.toDoublePrecise()}, " +
                        "${player2.name}: ${player2Change.toDoublePrecise()}",
                context =
                    mapOf(
                        "player1Change" to player1Change.toDoublePrecise(),
                        "player2Change" to player2Change.toDoublePrecise(),
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

        // Calculate rating changes and percentages using BigDecimal for precision
        val player1ChangeValue = player1NewRating.value.toBigDecimalPrecise() - player1.rating.value.toBigDecimalPrecise()
        val player1PercentChange =
            (player1ChangeValue.divide(player1.rating.value.toBigDecimalPrecise(), CALCULATION_SCALE, ROUNDING_MODE))
                .multiply(BigDecimal("100"))
                .setScale(2, ROUNDING_MODE)

        val player2ChangeValue = player2NewRating.value.toBigDecimalPrecise() - player2.rating.value.toBigDecimalPrecise()
        val player2PercentChange =
            (player2ChangeValue.divide(player2.rating.value.toBigDecimalPrecise(), CALCULATION_SCALE, ROUNDING_MODE))
                .multiply(BigDecimal("100"))
                .setScale(2, ROUNDING_MODE)

        val ratingChanges =
            mapOf(
                player1Id to
                    RatingChange(
                        change = player1ChangeValue.toDoublePrecise(),
                        percentChange = player1PercentChange.toDoublePrecise(),
                        previousRating = player1.rating,
                        newRating = player1NewRating,
                    ),
                player2Id to
                    RatingChange(
                        change = player2ChangeValue.toDoublePrecise(),
                        percentChange = player2PercentChange.toDoublePrecise(),
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
                totalGamesWinner.toBigDecimalPrecise()
                    .divide(totalGamesLoser.toBigDecimalPrecise(), CALCULATION_SCALE, ROUNDING_MODE)
            } else {
                BigDecimal("2.0") // Default for complete dominance
            }

        // Normalize dominance factor (1.0 = close match, 2.0+ = dominant win)
        val maxDominance = BigDecimal("2.5")
        val normalizedDominance = dominanceFactor.min(maxDominance)

        return MatchResult(
            winnerId = winnerId,
            winnerScore = BigDecimal.ONE,
            player1Score = if (winnerId == player1Id) BigDecimal.ONE else BigDecimal.ZERO,
            player2Score = if (winnerId == player2Id) BigDecimal.ONE else BigDecimal.ZERO,
            setsWon = maxOf(setsWonByPlayer1, setsWonByPlayer2),
            dominanceFactor = normalizedDominance,
        )
    }

    /**
     * Calculate rating changes using Elo algorithm.
     */
    private fun calculateRatingChanges(
        player1: PlayerProfile,
        player2: PlayerProfile,
        player1ActualScore: BigDecimal,
        player2ActualScore: BigDecimal,
        dominanceFactor: BigDecimal,
        audit: AuditTrail,
    ): Pair<BigDecimal, BigDecimal> {
        // Calculate expected scores based on rating differential
        val expectedPlayer1 =
            calculateExpectedScore(
                ratingA = player1.rating.value.toBigDecimalPrecise(),
                ratingB = player2.rating.value.toBigDecimalPrecise(),
            )
        val expectedPlayer2 = BigDecimal.ONE.setScale(CALCULATION_SCALE, ROUNDING_MODE) - expectedPlayer1

        audit.add(
            AuditEntry(
                message = "Expected scores - Player1: $expectedPlayer1, Player2: $expectedPlayer2",
                context =
                    mapOf(
                        "expectedPlayer1" to expectedPlayer1.toDoublePrecise(),
                        "expectedPlayer2" to expectedPlayer2.toDoublePrecise(),
                    ),
            ),
        )

        // Calculate base rating changes
        val baseChange1 = K_FACTOR * (player1ActualScore - expectedPlayer1)
        val baseChange2 = K_FACTOR * (player2ActualScore - expectedPlayer2)

        // Apply dominance factor (larger changes for more decisive matches)
        val adjustedChange1 =
            baseChange1.multiply(dominanceFactor)
                .setScale(CALCULATION_SCALE, ROUNDING_MODE)
        val adjustedChange2 =
            baseChange2.multiply(dominanceFactor)
                .setScale(CALCULATION_SCALE, ROUNDING_MODE)

        return Pair(adjustedChange1, adjustedChange2)
    }

    /**
     * Calculate expected score based on rating differential (Elo formula).
     * Uses BigDecimal for precise calculations.
     *
     * Formula: 1 / (1 + 10^((ratingB - ratingA) / SCALE_FACTOR))
     */
    private fun calculateExpectedScore(
        ratingA: BigDecimal,
        ratingB: BigDecimal,
    ): BigDecimal {
        // Calculate the exponent: (ratingB - ratingA) / SCALE_FACTOR
        val exponent =
            (ratingB - ratingA)
                .divide(SCALE_FACTOR, CALCULATION_SCALE, ROUNDING_MODE)

        // Use Math.pow for the exponential calculation
        // Note: This is the only place we use Double for computation,
        // but we immediately convert back to BigDecimal with proper precision
        val powerResult = 10.0.pow(exponent.toDouble())
        val powerResultBd = BigDecimal(powerResult.toString()).setScale(CALCULATION_SCALE, ROUNDING_MODE)

        // Calculate: 1 / (1 + powerResult)
        val denominator = BigDecimal.ONE.setScale(CALCULATION_SCALE, ROUNDING_MODE) + powerResultBd
        return BigDecimal.ONE.divide(denominator, CALCULATION_SCALE, ROUNDING_MODE)
    }

    /**
     * Apply rating change with system-specific constraints.
     */
    private fun applyRatingChange(
        rating: Rating,
        change: BigDecimal,
        audit: AuditTrail,
    ): Rating {
        return when (rating.system) {
            RatingSystem.NTRP -> applyNTRPChange(rating = rating, change = change, audit = audit)
            RatingSystem.UTR -> applyUTRChange(rating = rating, change = change, audit = audit)
        }
    }

    /**
     * Apply rating change for NTRP (continuous values, range 1.0-7.0).
     * Rounds to 2 decimal places for NTRP display.
     */
    private fun applyNTRPChange(
        rating: Rating,
        change: BigDecimal,
        audit: AuditTrail,
    ): Rating {
        val originalValue = rating.value.toBigDecimalPrecise()
        val newValue = originalValue + change

        // Round to 2 decimal places for NTRP
        val rounded = newValue.setScale(2, ROUNDING_MODE)

        // Clamp to valid NTRP range
        val clamped =
            if (rounded < NTRP_MIN) {
                NTRP_MIN
            } else if (rounded > NTRP_MAX) {
                NTRP_MAX
            } else {
                rounded
            }

        audit.add(
            AuditEntry(
                message =
                    "NTRP change: ${rating.value} + ${change.toDoublePrecise()} = " +
                        "${newValue.toDoublePrecise()} -> rounded ${rounded.toDoublePrecise()} -> " +
                        "clamped ${clamped.toDoublePrecise()}",
                context =
                    mapOf(
                        "system" to "NTRP",
                        "original" to rating.value,
                        "change" to change.toDoublePrecise(),
                        "newValue" to newValue.toDoublePrecise(),
                        "rounded" to rounded.toDoublePrecise(),
                        "clamped" to clamped.toDoublePrecise(),
                    ),
            ),
        )

        return Rating(value = clamped.toDoublePrecise(), system = RatingSystem.NTRP)
    }

    /**
     * Apply rating change for UTR (decimal values allowed, minimum 1.0).
     * Rounds to 1 decimal place for UTR display.
     */
    private fun applyUTRChange(
        rating: Rating,
        change: BigDecimal,
        audit: AuditTrail,
    ): Rating {
        val originalValue = rating.value.toBigDecimalPrecise()
        val newValue = originalValue + change

        // Round to 1 decimal place for UTR
        val rounded = newValue.setScale(1, ROUNDING_MODE)

        // Ensure minimum UTR rating
        val clamped = if (rounded < UTR_MIN) UTR_MIN else rounded

        audit.add(
            AuditEntry(
                message =
                    "UTR change: ${rating.value} + ${change.toDoublePrecise()} = " +
                        "${newValue.toDoublePrecise()} -> rounded ${rounded.toDoublePrecise()} -> " +
                        "clamped ${clamped.toDoublePrecise()}",
                context =
                    mapOf(
                        "system" to "UTR",
                        "original" to rating.value,
                        "change" to change.toDoublePrecise(),
                        "newValue" to newValue.toDoublePrecise(),
                        "rounded" to rounded.toDoublePrecise(),
                        "clamped" to clamped.toDoublePrecise(),
                    ),
            ),
        )

        return Rating(value = clamped.toDoublePrecise(), system = RatingSystem.UTR)
    }

    private data class MatchResult(
        val winnerId: String,
        val winnerScore: BigDecimal,
        val player1Score: BigDecimal,
        val player2Score: BigDecimal,
        val setsWon: Int,
        val dominanceFactor: BigDecimal,
    )
}
