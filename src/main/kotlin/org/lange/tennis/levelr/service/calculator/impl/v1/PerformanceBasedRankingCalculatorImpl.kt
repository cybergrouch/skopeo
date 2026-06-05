package org.lange.tennis.levelr.service.calculator.impl.v1

import org.lange.tennis.levelr.dto.RankingCalculationRequest
import org.lange.tennis.levelr.dto.RankingCalculationResponse
import org.lange.tennis.levelr.dto.RatingChange
import org.lange.tennis.levelr.model.MatchScore
import org.lange.tennis.levelr.model.PlayerProfile
import org.lange.tennis.levelr.model.Rating
import org.lange.tennis.levelr.model.RatingSystem
import org.lange.tennis.levelr.model.calculateDominanceFactor
import org.lange.tennis.levelr.model.loser
import org.lange.tennis.levelr.model.matchScore
import org.lange.tennis.levelr.model.totalGamesWon
import org.lange.tennis.levelr.service.calculator.AuditEntry
import org.lange.tennis.levelr.service.calculator.AuditTrail
import org.lange.tennis.levelr.service.calculator.RankingCalculationResult
import org.lange.tennis.levelr.service.calculator.RankingCalculator
import org.lange.tennis.levelr.service.calculator.impl.bd
import org.lange.tennis.levelr.service.calculator.impl.divideBy
import org.lange.tennis.levelr.service.calculator.impl.toStringPrecise
import java.math.BigDecimal

/**
 * Performance-based ranking calculator with margin-aware adjustments.
 *
 * This implementation uses an Elo-based algorithm with several enhancements:
 * - 6-3 score as baseline expectation (no rating change)
 * - Inverted changes when favorite wins below expectation (6-4, 6-5)
 * - Dynamic max change capped at ratingDiff/3 (takes 3 upsets to close gap)
 * - 6-0 paradox fixed (shutouts properly rewarded)
 * - Upset multiplier for unexpected results
 *
 * All internal calculations use BigDecimal with 6 decimal places precision
 * to ensure accurate and predictable results.
 */
class PerformanceBasedRankingCalculatorImpl : RankingCalculator {
    companion object {
        private val ZERO = "0.0".bd
        private val ONE = "1.0".bd

        // NTRP-specific constants
        private val NTRP_MIN = "1.0".bd
        private val NTRP_MAX = "7.0".bd
        private val NTRP_RANGE = NTRP_MAX - NTRP_MIN // 6.0

        // UTR-specific constants
        private val UTR_MIN = ONE
        private val UTR_MAX = "16.0".bd
        private val UTR_RANGE = "15.0".bd // Practical range (1.0-16.0 for professional level)

        // K-factor for NTRP (base calibration)
        // K=0.16 chosen to produce typical changes of:
        //   - Equal players, close match (6-4): ±0.032
        //   - Equal players, dominant match (6-0): ±0.160
        //   - Moderate upset (0.5 gap reversed): ±0.321
        // This ensures gradual convergence without excessive volatility
        private val K_FACTOR_NTRP = "0.16".bd

        // Competitive threshold as percentage of rating range (8.3% for all systems)
        // 8.3% ≈ 1/12 of range, representing a "half-level" skill difference
        //   - NTRP: 8.3% × 6.0 = 0.5 points (e.g., 4.0 vs 4.5)
        //   - UTR: 8.3% × 15.0 = 1.25 points (e.g., 10.0 vs 11.25)
        // Matches within this threshold produce full performance-based changes
        // Matches beyond this threshold (expected outcomes) produce reduced/zero changes
        private val COMPETITIVE_THRESHOLD_PCT = "0.083".bd
    }

    /**
     * Calculate updated rankings based on match results.
     * Returns both the calculation result and an audit trail.
     */
    override fun calculate(request: RankingCalculationRequest): RankingCalculationResult {
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

        with(request.matchScore) {
            audit.add(
                AuditEntry(
                    message =
                        "Match result - Winner: $winner, Score: $matchScore",
                    context =
                        mapOf(
                            "winnerId" to winner,
                            "winnerScore" to totalGamesWon(playerId = winner),
                            "loserId" to loser,
                            "loserScore" to totalGamesWon(playerId = loser),
                            "winnerDominanceFactor" to calculateDominanceFactor(playerId = winner),
                            "loserDominanceFactor" to calculateDominanceFactor(playerId = loser),
                        ),
                ),
            )

            // Calculate Elo-based rating changes
            val (player1Change, player2Change) =
                calculateRatingAdjustments(
                    player1 = player1,
                    player2 = player2,
                    ratingSystem = player1.rating.system,
                    matchScore = request.matchScore,
                    audit = audit,
                )

            audit.add(
                AuditEntry(
                    message =
                        "Rating changes - ${player1.name}: ${player1.rating.value} to ${player1Change.toStringPrecise()}, " +
                            "${player2.name}: ${player2.rating.value} to ${player2Change.toStringPrecise()}",
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

            // Calculate percent changes
            val player1PercentChange = calculatePercentChange(player1.rating.value.bd, player1NewRating.value.bd)
            val player2PercentChange = calculatePercentChange(player2.rating.value.bd, player2NewRating.value.bd)

            val ratingChanges =
                mapOf(
                    player1Id to
                        RatingChange(
                            change = (player1NewRating.value.bd - player1.rating.value.bd).toStringPrecise(),
                            previousRating = player1.rating,
                            newRating = player1NewRating,
                            percentChange = player1PercentChange,
                        ),
                    player2Id to
                        RatingChange(
                            change = (player2NewRating.value.bd - player2.rating.value.bd).toStringPrecise(),
                            previousRating = player2.rating,
                            newRating = player2NewRating,
                            percentChange = player2PercentChange,
                        ),
                )

            // Create updated player profiles
            val updatedPlayers =
                mapOf(
                    player1Id to player1.copy(rating = player1NewRating),
                    player2Id to player2.copy(rating = player2NewRating),
                )

            val response =
                RankingCalculationResponse(
                    ratingChanges = ratingChanges,
                    players = updatedPlayers,
                )

            return RankingCalculationResult(
                response = response,
                audit = audit.getEntries(),
            )
        }
    }

    /**
     * Calculate rating changes using performance-based Elo algorithm.
     *
     * This algorithm considers whether players performed above or below expectations
     * based on the rating differential and adjusts changes accordingly.
     *
     * **Five Adjustment Cases:**
     * 1. **Equal Ratings** (diff < 0.01): Standard Elo × dominance factor
     * 2. **Upset** (underdog wins): Cap at ±(ratingDiff/3)
     * 3. **Met Expectations** (margin ≈ expected): Zero change
     * 4. **Underperformed** (margin < expected): Inverted changes × multiplier
     * 5. **Overperformed** (margin > expected): Capped changes based on margin
     *
     * **Zero-Sum Property:**
     * - Before boundary clamping: change1 + change2 = 0.0 (strictly zero-sum)
     * - After boundary clamping: May NOT be zero-sum (boundary enforcement takes precedence)
     *
     * Example: 7.0 (max) wins, calculated +0.2 → clamped to 0.0, but loser still loses 0.2
     * This is intentional: maintaining accurate ratings at boundaries is more important than zero-sum.
     *
     * @return Pair of (player1Change, player2Change) before boundary clamping
     */
    private fun calculateRatingAdjustments(
        player1: PlayerProfile,
        player2: PlayerProfile,
        matchScore: MatchScore,
        ratingSystem: RatingSystem,
        audit: AuditTrail,
    ): Pair<BigDecimal, BigDecimal> {
        fun PlayerProfile.getRatingDifference(
            opponent: PlayerProfile,
            ratingScale: BigDecimal,
        ): BigDecimal = (this.rating.value.bd - opponent.rating.value.bd).min(ratingScale)

        fun calculateDominanceFactor(
            player: PlayerProfile,
            opponent: PlayerProfile,
            ratingScale: BigDecimal,
        ): BigDecimal =
            player.getRatingDifference(
                opponent = opponent,
                ratingScale = ratingScale,
            ) / ratingScale

        /**
         * Calculate rating adjustment using simplified formula with normalized gaps.
         *
         * Formula: K × dominance × scale × sign
         *
         * where:
         *   normalized_gap = rating_gap / rating_range
         *   scale = upset_factor (if upset) OR competitive_factor (otherwise)
         *   upset_factor = (normalized_gap / threshold_pct) × upset_multiplier
         *   competitive_factor = max(0, (threshold_pct - normalized_gap) / threshold_pct)
         *
         * Pluggable constants:
         *   - threshold_pct: 8.3% of rating range (same for all systems)
         *   - upset_multiplier: bonus for upsets (2.0)
         *
         * Rating gaps are normalized by rating range to ensure proportional fairness:
         *   - 0.5 NTRP gap = 0.5/6.0 = 8.3% of range
         *   - 1.25 UTR gap = 1.25/15.0 = 8.3% of range
         *   - Both produce identical competitive factors → consistent 2.5× K-factor scaling
         *
         * This approach reduces from 5 branches to 2 while maintaining proper handling of:
         *   - Expected wins (higher beats lower with large gap) → minimal change
         *   - Upsets (underdog wins) → significant change with upset multiplier
         *   - Competitive matches (gap ≤ threshold) → performance-based change
         *   - Equal players (gap = 0) → full performance-based change
         */
        fun PlayerProfile.calculateRankingAdjustment(
            opponent: PlayerProfile,
            matchScore: MatchScore,
            ratingScale: BigDecimal,
        ): BigDecimal {
            val dominance = matchScore.calculateDominanceFactor(playerId = this.playerId)
            val dominanceMagnitude = dominance.abs()
            val ratingAdvantage = this.rating.value.bd - opponent.rating.value.bd
            val absAdvantage = ratingAdvantage.abs()
            val isWinner = matchScore.winner == this.playerId

            // Get rating range for normalization
            val ratingRange =
                when (ratingSystem) {
                    RatingSystem.NTRP -> NTRP_RANGE
                    RatingSystem.UTR -> UTR_RANGE
                }

            // Normalize rating gap as percentage of range
            val normalizedGap = absAdvantage.divideBy(ratingRange)

            // Pluggable constants
            val thresholdPct = COMPETITIVE_THRESHOLD_PCT
            // Upset multiplier doubles the impact of unexpected results
            // A 2.0 multiplier ensures upsets produce significant rating changes
            // compared to expected outcomes, reflecting that upsets indicate
            // rating inaccuracy that should be corrected more aggressively
            val upsetMultiplier = "2.0".bd

            // Calculate scale based on upset vs competitive/expected scenario
            val scale =
                if ((isWinner && ratingAdvantage < ZERO) || (!isWinner && ratingAdvantage > ZERO)) {
                    // Upset: underdog wins OR favorite loses
                    // Use gap-based scaling with upset multiplier
                    normalizedGap.divideBy(thresholdPct) * upsetMultiplier
                } else {
                    // Competitive or expected outcome
                    // Use advantage factor that decreases as gap increases
                    (thresholdPct - normalizedGap).divideBy(thresholdPct).max(ZERO)
                }

            val sign = if (isWinner) ONE else -ONE
            return ratingScale * dominanceMagnitude * scale * sign
        }

        val ratingScale =
            when (ratingSystem) {
                RatingSystem.NTRP -> K_FACTOR_NTRP
                RatingSystem.UTR -> K_FACTOR_NTRP * UTR_RANGE.divideBy(divisor = NTRP_RANGE)
            }

        // Calculate base Elo changes
        val player1RankingAdjustment =
            player1.calculateRankingAdjustment(
                opponent = player2,
                matchScore = matchScore,
                ratingScale = ratingScale,
            )
        val player2RankingAdjustment =
            player2.calculateRankingAdjustment(
                opponent = player1,
                matchScore = matchScore,
                ratingScale = ratingScale,
            )

        audit.add(
            AuditEntry(
                message =
                    "Ranking Adjustment Calculation",
                context =
                    mapOf(
                        "player1RankingAdjustment" to player1RankingAdjustment.toStringPrecise(),
                        "player2RankingAdjustment" to player2RankingAdjustment.toStringPrecise(),
                    ),
            ),
        )

        return player1RankingAdjustment to player2RankingAdjustment
    }

    /**
     * Apply rating change with system-specific constraints.
     */
    private fun applyRatingChange(
        rating: Rating,
        change: BigDecimal,
        audit: AuditTrail,
    ): Rating =
        when (rating.system) {
            RatingSystem.NTRP -> applyNTRPChange(rating = rating, change = change, audit = audit)
            RatingSystem.UTR -> applyUTRChange(rating = rating, change = change, audit = audit)
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
        val originalValue = rating.value.bd
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

        return rating.copy(
            value = clamped.toStringPrecise(),
        )
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
        val originalValue = rating.value.bd
        val newValue = originalValue + change

        // Clamp to valid UTR range (no rounding - keep 6 decimal precision)
        val clamped =
            if (newValue < UTR_MIN) {
                UTR_MIN
            } else if (newValue > UTR_MAX) {
                UTR_MAX
            } else {
                newValue
            }

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

        return rating.copy(
            value = clamped.toStringPrecise(),
        )
    }

    /**
     * Calculate percent change between old and new rating values.
     * Returns "0.000000" if old rating is zero to avoid division by zero.
     */
    private fun calculatePercentChange(
        oldValue: BigDecimal,
        newValue: BigDecimal,
    ): String {
        if (oldValue == ZERO) {
            return "0.000000"
        }
        val percentChange = ((newValue - oldValue) / oldValue * "100.0".bd)
        return percentChange.toStringPrecise()
    }
}
