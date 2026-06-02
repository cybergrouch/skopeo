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

        private val ZERO = "0.0".bd
        private val ONE = "1.0".bd

        // K-factor controls how much ratings change per match
        private val K_FACTOR = "32.0".bd

        // Scale factor for expected score calculation
        private val SCALE_FACTOR = "400.0".bd

        // NTRP-specific constants
        private val NTRP_MIN = "1.0".bd
        private val NTRP_MAX = "7.0".bd

        // UTR-specific constants
        private val UTR_MIN = ONE

        // Helper extension functions for BigDecimal conversions (Kotlin idioms)
        private fun Double.toBigDecimalPrecise(): BigDecimal = BigDecimal(this.toString()).setScale(CALCULATION_SCALE, ROUNDING_MODE)

        private fun Int.toBigDecimalPrecise(): BigDecimal = BigDecimal(this).setScale(CALCULATION_SCALE, ROUNDING_MODE)

        private fun String.toBigDecimalPrecise(): BigDecimal = BigDecimal(this).setScale(CALCULATION_SCALE, ROUNDING_MODE)

        private fun BigDecimal.toStringPrecise(): String =
            this.setScale(CALCULATION_SCALE, ROUNDING_MODE)
                .stripTrailingZeros()
                .toPlainString()

        private fun BigDecimal.divideBy(divisor: BigDecimal): BigDecimal = this.divide(divisor, CALCULATION_SCALE, ROUNDING_MODE)

        private fun BigDecimal.multiplyWith(number: Double): BigDecimal = this.multiply(number.toBigDecimalPrecise())

        private val BigDecimal.asPowOfTen: BigDecimal get() = 10.0.pow(this.toDouble()).toBigDecimalPrecise()

        // Kotlin idiom: Extension properties for cleaner BigDecimal creation
        private val String.bd: BigDecimal get() = this.toBigDecimalPrecise()

        private val BigDecimal.scaleUp get() = this.multiplyWith(number = 100.00)
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
                        "Score: ${matchResult.winnerScore.toStringPrecise()}, Sets: ${matchResult.setsWon}",
                context =
                    mapOf(
                        "winnerId" to matchResult.winnerId,
                        "winnerScore" to matchResult.winnerScore.toStringPrecise(),
                        "setsWon" to matchResult.setsWon,
                        "dominanceFactor" to matchResult.dominanceFactor.toStringPrecise(),
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
                    "Rating changes - ${player1.name}: ${player1Change.toStringPrecise()}, " +
                        "${player2.name}: ${player2Change.toStringPrecise()}",
                context =
                    mapOf(
                        "player1Change" to player1Change.toStringPrecise(),
                        "player2Change" to player2Change.toStringPrecise(),
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
            (
                player1ChangeValue.divideBy(divisor = player1.rating.value.toBigDecimalPrecise()).scaleUp
            ).setScale(2, ROUNDING_MODE)

        val player2ChangeValue = player2NewRating.value.toBigDecimalPrecise() - player2.rating.value.toBigDecimalPrecise()
        val player2PercentChange =
            (
                player2ChangeValue.divideBy(divisor = player2.rating.value.toBigDecimalPrecise()).scaleUp
            ).setScale(2, ROUNDING_MODE)

        val ratingChanges =
            mapOf(
                player1Id to
                    RatingChange(
                        change = player1ChangeValue.toStringPrecise(),
                        percentChange = player1PercentChange.toStringPrecise(),
                        previousRating = player1.rating,
                        newRating = player1NewRating,
                    ),
                player2Id to
                    RatingChange(
                        change = player2ChangeValue.toStringPrecise(),
                        percentChange = player2PercentChange.toStringPrecise(),
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
     * Analyze a match result to determine winner and calculate dominance factor.
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
                    .divideBy(divisor = totalGamesLoser.toBigDecimalPrecise())
            } else {
                "2.0".bd // Default for complete dominance
            }

        // Normalize dominance factor (1.0 = close match, 2.0+ = dominant win)
        val maxDominance = "2.5".bd
        val normalizedDominance = dominanceFactor.min(maxDominance)

        return MatchResult(
            winnerId = winnerId,
            winnerScore = ONE,
            player1Score = if (winnerId == player1Id) ONE else ZERO,
            player2Score = if (winnerId == player2Id) BigDecimal.ONE else ZERO,
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
        val expectedPlayer2 = ONE - expectedPlayer1

        audit.add(
            AuditEntry(
                message = "Expected scores - Player1: $expectedPlayer1, Player2: $expectedPlayer2",
                context =
                    mapOf(
                        "expectedPlayer1" to expectedPlayer1.toStringPrecise(),
                        "expectedPlayer2" to expectedPlayer2.toStringPrecise(),
                    ),
            ),
        )

        // Calculate base rating changes
        val baseChange1 = K_FACTOR * (player1ActualScore - expectedPlayer1)
        val baseChange2 = K_FACTOR * (player2ActualScore - expectedPlayer2)

        // Apply dominance factor (larger changes for more decisive matches)
        val adjustedChange1 = baseChange1 * dominanceFactor
        val adjustedChange2 = baseChange2 * dominanceFactor

        return Pair(first = adjustedChange1, second = adjustedChange2)
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
            (ratingB - ratingA).divideBy(divisor = SCALE_FACTOR)

        // Calculate: 1 / (1 + powerResult)
        return ONE.divideBy(ONE + exponent.asPowOfTen)
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

        // Clamp to valid NTRP range (no rounding - keep 6 decimal precision)
        val clamped =
            if (newValue < NTRP_MIN) {
                NTRP_MIN
            } else if (newValue > NTRP_MAX) {
                NTRP_MAX
            } else {
                newValue
            }

        audit.add(
            AuditEntry(
                message =
                    "NTRP change: ${rating.value} + ${change.toStringPrecise()} = " +
                        "${newValue.toStringPrecise()} -> clamped ${clamped.toStringPrecise()}",
                context =
                    mapOf(
                        "system" to "NTRP",
                        "original" to rating.value,
                        "change" to change.toStringPrecise(),
                        "newValue" to newValue.toStringPrecise(),
                        "clamped" to clamped.toStringPrecise(),
                    ),
            ),
        )

        return Rating(value = clamped.toStringPrecise(), system = RatingSystem.NTRP)
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

        // Ensure minimum UTR rating (no rounding - keep 6 decimal precision)
        val clamped = if (newValue < UTR_MIN) UTR_MIN else newValue

        audit.add(
            AuditEntry(
                message =
                    "UTR change: ${rating.value} + ${change.toStringPrecise()} = " +
                        "${newValue.toStringPrecise()} -> clamped ${clamped.toStringPrecise()}",
                context =
                    mapOf(
                        "system" to "UTR",
                        "original" to rating.value,
                        "change" to change.toStringPrecise(),
                        "newValue" to newValue.toStringPrecise(),
                        "clamped" to clamped.toStringPrecise(),
                    ),
            ),
        )

        return Rating(value = clamped.toStringPrecise(), system = RatingSystem.UTR)
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
