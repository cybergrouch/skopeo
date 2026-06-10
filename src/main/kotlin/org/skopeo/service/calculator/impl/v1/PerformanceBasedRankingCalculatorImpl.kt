package org.skopeo.service.calculator.impl.v1

import org.skopeo.dto.RankingCalculationRequest
import org.skopeo.dto.RankingCalculationResponse
import org.skopeo.dto.RatingChange
import org.skopeo.model.MatchScore
import org.skopeo.model.PlayerProfile
import org.skopeo.model.Rating
import org.skopeo.model.RatingSystem
import org.skopeo.model.calculateDominanceFactor
import org.skopeo.model.matchScore
import org.skopeo.service.calculator.AuditEntry
import org.skopeo.service.calculator.AuditTrail
import org.skopeo.service.calculator.RankingCalculationResult
import org.skopeo.service.calculator.RankingCalculator
import org.skopeo.service.calculator.impl.bd
import org.skopeo.service.calculator.impl.divideBy
import org.skopeo.service.calculator.impl.toStringPrecise
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
 * - **Optional USTA NTRP Dynamic-style rating smoothing**
 *
 * ## Rating Smoothing
 *
 * Rating smoothing (inspired by USTA NTRP Dynamic Algorithm) creates more stable ratings
 * by blending the calculated new rating with the previous rating:
 *
 * ```
 * calculatedRating = previousRating + rawChange
 * smoothedRating = (calculatedRating × factor) + (previousRating × (1 - factor))
 * finalChange = smoothedRating - previousRating
 * ```
 *
 * This is mathematically equivalent to:
 * ```
 * smoothedChange = rawChange × smoothingFactor
 * ```
 *
 * ### Smoothing Factor Values:
 * - **0.0**: No change (rating never moves)
 * - **0.3**: Conservative (30% of calculated change applied)
 * - **0.5**: USTA NTRP Dynamic style (50% - standard averaging)
 * - **0.7**: Aggressive (70% of calculated change applied)
 * - **1.0**: Full change (equivalent to smoothing disabled)
 *
 * ### Benefits:
 * - **Stability**: Prevents wild swings from single matches
 * - **Convergence**: Gradual movement toward true skill level
 * - **Noise reduction**: Dampens impact of outlier performances
 * - **Player experience**: Ratings feel more predictable and fair
 *
 * ### Usage:
 * ```kotlin
 * val request = RankingCalculationRequest(
 *     players = mapOf(...),
 *     matchScore = MatchScore(...),
 *     options = RatingCalculationOptions(
 *         smoothingEnabled = true,
 *         smoothingFactor = 0.5  // USTA style
 *     )
 * )
 * ```
 *
 * ### Example Impact:
 * Equal 4.0 NTRP players, 6-0 score:
 * - Without smoothing: +0.160 / -0.160
 * - With 0.3 smoothing: +0.048 / -0.048 (30% applied)
 * - With 0.5 smoothing: +0.080 / -0.080 (50% applied)
 * - With 0.7 smoothing: +0.112 / -0.112 (70% applied)
 *
 * ### Important Notes:
 * - Smoothing is applied BEFORE boundary clamping (1.0-7.0 NTRP, 1.0-16.0 UTR)
 * - Zero-sum property is preserved: sum of changes remains zero before clamping
 * - UTR and NTRP both support smoothing with 2.5× K-factor scaling maintained
 * - Default: smoothing disabled for backward compatibility
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
        //
        // How this affects changes:
        //   - Gap < 8.3%: Full performance-based changes (competitive match)
        //   - Gap > 8.3%: Reduced changes (expected outcome has less information value)
        //   - Gap >> 8.3%: Near-zero changes (outcome was highly predictable)
        //
        // The threshold acts as a transition point: matches closer than this are treated
        // as skill-revealing, while wider gaps indicate the result was expected.
        private val COMPETITIVE_THRESHOLD_PCT = "0.083".bd
    }

    /**
     * Calculate updated rankings based on match results.
     * Returns both the calculation result and an audit trail.
     */
    override fun calculate(request: RankingCalculationRequest): RankingCalculationResult {
        val audit = AuditTrail()

        // Extract teams and players
        val teamIds = request.teams.keys.toList()
        val team1Id = teamIds[0]
        val team2Id = teamIds[1]
        val team1 = request.teams[team1Id]!!
        val team2 = request.teams[team2Id]!!

        // For singles: extract the single player from each team
        val player1 = team1.getSinglePlayer()
        val player2 = team2.getSinglePlayer()
        val player1Id = player1.playerId
        val player2Id = player2.playerId

        audit.add(
            AuditEntry(
                message =
                    "Calculating ranking for ${team1.name} (${player1.name}, ${player1.rating.value}) vs " +
                        "${team2.name} (${player2.name}, ${player2.rating.value})",
                context =
                    mapOf(
                        "team1" to team1.name,
                        "team2" to team2.name,
                        "player1" to player1.name,
                        "player1Rating" to player1.rating.value,
                        "player2" to player2.name,
                        "player2Rating" to player2.rating.value,
                        "ratingSystem" to player1.rating.system.name,
                    ),
            ),
        )

        with(request.matchScore) {
            audit.add(
                AuditEntry(
                    message =
                        "Match result - Winner: $winnerTeamId, Score: $matchScore",
                    context =
                        mapOf(
                            "winnerTeamId" to winnerTeamId,
                            "loserTeamId" to loserTeamId,
                            "score" to matchScore,
                            "winnerDominanceFactor" to calculateDominanceFactor(teamId = winnerTeamId),
                            "loserDominanceFactor" to calculateDominanceFactor(teamId = loserTeamId),
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
                    player1TeamId = team1Id,
                    player2TeamId = team2Id,
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
            val options = request.options ?: org.skopeo.model.RatingCalculationOptions()
            val player1NewRating = applyRatingChange(rating = player1.rating, change = player1Change, options = options, audit = audit)
            val player2NewRating = applyRatingChange(rating = player2.rating, change = player2Change, options = options, audit = audit)

            // Calculate percent changes
            val player1PercentChange = calculatePercentChange(player1.rating.value.bd, player1NewRating.value.bd)
            val player2PercentChange = calculatePercentChange(player2.rating.value.bd, player2NewRating.value.bd)

            // Determine if published levels changed (v1: levels are embedded in Rating objects)
            val player1LevelChanged = player1.rating.publishedLevel.value != player1NewRating.publishedLevel.value
            val player2LevelChanged = player2.rating.publishedLevel.value != player2NewRating.publishedLevel.value

            val ratingChanges =
                mapOf(
                    player1Id to
                        RatingChange(
                            change = (player1NewRating.value.bd - player1.rating.value.bd).toStringPrecise(),
                            previousRating = player1.rating,
                            newRating = player1NewRating,
                            percentChange = player1PercentChange,
                            levelChanged = player1LevelChanged,
                        ),
                    player2Id to
                        RatingChange(
                            change = (player2NewRating.value.bd - player2.rating.value.bd).toStringPrecise(),
                            previousRating = player2.rating,
                            newRating = player2NewRating,
                            percentChange = player2PercentChange,
                            levelChanged = player2LevelChanged,
                        ),
                )

            // Build updated player profiles
            val updatedPlayer1 = player1.copy(rating = player1NewRating)
            val updatedPlayer2 = player2.copy(rating = player2NewRating)

            val updatedPlayers =
                mapOf(
                    player1Id to updatedPlayer1,
                    player2Id to updatedPlayer2,
                )

            // Build updated teams with updated players
            val updatedTeam1 = team1.copy(players = listOf(updatedPlayer1))
            val updatedTeam2 = team2.copy(players = listOf(updatedPlayer2))

            val updatedTeams =
                mapOf(
                    team1Id to updatedTeam1,
                    team2Id to updatedTeam2,
                )

            val response =
                RankingCalculationResponse(
                    ratingChanges = ratingChanges,
                    players = updatedPlayers,
                    teams = updatedTeams,
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
        player1TeamId: String,
        player2TeamId: String,
    ): Pair<BigDecimal, BigDecimal> {
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
            teamId: String,
        ): BigDecimal {
            // Dominance factor measures match closeness as the average of per-set efficiency:
            // each set contributes (games_won - games_lost) / (games_won + games_lost),
            // and the match dominance is the mean across sets.
            // Range: -1.0 (shutout loss) to +1.0 (shutout win)
            // Examples:
            //   - 6-0: (6-0)/6 = 1.0 (complete dominance)
            //   - 6-4: (6-4)/10 = 0.2 (competitive, slight edge)
            //   - 6-0, 3-6, 6-2: (1.0 - 0.333 + 0.5)/3 = 0.389 (sets averaged)
            val dominance = matchScore.calculateDominanceFactor(teamId = teamId)
            val dominanceMagnitude = dominance.abs()
            val ratingAdvantage = this.rating.value.bd - opponent.rating.value.bd
            val absAdvantage = ratingAdvantage.abs()
            val isWinner = matchScore.winnerTeamId == teamId

            // Get rating range for normalization
            val ratingRange =
                when (ratingSystem) {
                    RatingSystem.NTRP -> NTRP_RANGE
                    RatingSystem.UTR -> UTR_RANGE
                }

            // Normalize rating gap as percentage of range
            // This ensures fair treatment across rating systems with different scales:
            //   - 0.5 NTRP gap → 0.5/6.0 = 8.3% normalized
            //   - 1.25 UTR gap → 1.25/15.0 = 8.3% normalized
            // Both produce identical scale factors → consistent K-factor ratio (2.5×)
            val normalizedGap = absAdvantage.divideBy(ratingRange)

            // Pluggable constants
            val thresholdPct = COMPETITIVE_THRESHOLD_PCT

            // Upset multiplier: Why 2.0?
            // When an underdog wins, it provides strong evidence that ratings are inaccurate.
            // A multiplier of 2.0 ensures upsets produce 2× the impact of expected outcomes,
            // allowing ratings to converge faster toward true skill levels.
            //
            // Example with 0.5 NTRP gap (8.3% normalized):
            //   - If favorite wins: scale ≈ 0.0 (near threshold), minimal change
            //   - If underdog wins: scale = (0.083 / 0.083) × 2.0 = 2.0, doubled change
            //
            // This creates asymmetry: surprising results move ratings more than predictable ones.
            val upsetMultiplier = "2.0".bd

            val isUpset = (isWinner && ratingAdvantage < ZERO) || (!isWinner && ratingAdvantage > ZERO)
            val scale =
                calculateScale(
                    isUpset = isUpset,
                    normalizedGap = normalizedGap,
                    thresholdPct = thresholdPct,
                    upsetMultiplier = upsetMultiplier,
                )

            val sign = if (isWinner) ONE else -ONE
            val signLabel = if (isWinner) "+1" else "-1"

            // Final formula: change = K × dominance × scale × sign
            // Where:
            //   K = K-factor (0.16 for NTRP, 0.4 for UTR)
            //   dominance = average per-set (gamesWon − gamesLost)/(gamesWon + gamesLost), magnitude 0.0 to 1.0
            //   scale = upset_factor OR competitive_factor (calculated above)
            //   sign = +1 for winner, -1 for loser (ensures zero-sum before clamping)
            val change = ratingScale * dominanceMagnitude * scale * sign

            // Log every factor that contributed to the change, so the audit trail
            // can reconstruct the full calculation: change = K × dominance × scale × sign
            audit.add(
                AuditEntry(
                    message =
                        "Adjustment factors - ${this.name}: change = K × dominance × scale × sign = " +
                            "${ratingScale.toStringPrecise()} × ${dominanceMagnitude.toStringPrecise()} × " +
                            "${scale.toStringPrecise()} × $signLabel = ${change.toStringPrecise()}",
                    context =
                        mapOf(
                            "playerId" to this.playerId,
                            "kFactor" to ratingScale.toStringPrecise(),
                            "dominance" to dominance.toStringPrecise(),
                            "ratingGap" to absAdvantage.toStringPrecise(),
                            "normalizedGap" to normalizedGap.toStringPrecise(),
                            "competitiveThresholdPct" to thresholdPct.toStringPrecise(),
                            "isUpset" to isUpset.toString(),
                            "upsetMultiplier" to upsetMultiplier.toStringPrecise(),
                            "scale" to scale.toStringPrecise(),
                            "sign" to signLabel,
                            "change" to change.toStringPrecise(),
                        ),
                ),
            )

            return change
        }

        // K-factor scales proportionally by rating range to maintain consistent volatility
        // UTR has a wider range (15.0) than NTRP (6.0), so K_UTR = K_NTRP × (15.0/6.0) = K_NTRP × 2.5
        // This ensures that a 1-point UTR gap produces the same % change as a 0.4-point NTRP gap
        // Example: 1.0 UTR = 1.0/15.0 = 6.67% of range ≈ 0.4 NTRP = 0.4/6.0 = 6.67% of range
        val ratingScale =
            when (ratingSystem) {
                RatingSystem.NTRP -> K_FACTOR_NTRP
                RatingSystem.UTR -> K_FACTOR_NTRP * UTR_RANGE.divideBy(divisor = NTRP_RANGE) // Result: 0.16 × 2.5 = 0.4
            }

        // Calculate base Elo changes
        val player1RankingAdjustment =
            player1.calculateRankingAdjustment(
                opponent = player2,
                matchScore = matchScore,
                ratingScale = ratingScale,
                teamId = player1TeamId,
            )
        val player2RankingAdjustment =
            player2.calculateRankingAdjustment(
                opponent = player1,
                matchScore = matchScore,
                ratingScale = ratingScale,
                teamId = player2TeamId,
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
     * Calculate the scale factor based on upset vs competitive/expected scenario.
     *
     * Two cases determine how much ratings should change:
     *
     * Case 1: UPSET (underdog wins OR favorite loses)
     *   Formula: scale = (normalizedGap / threshold) × upsetMultiplier
     *   Example: 0.5 NTRP underdog wins (8.3% gap)
     *     scale = (0.083 / 0.083) × 2.0 = 2.0
     *   Larger gaps = bigger surprises = larger changes
     *
     * Case 2: EXPECTED/COMPETITIVE (favorite wins OR even match)
     *   Formula: scale = max(0, (threshold - normalizedGap) / threshold)
     *   Example 1: Equal players (0% gap)
     *     scale = (0.083 - 0.0) / 0.083 = 1.0 (full change)
     *   Example 2: 0.5 NTRP favorite wins (8.3% gap)
     *     scale = (0.083 - 0.083) / 0.083 = 0.0 (no change, as expected)
     *   Example 3: 1.0 NTRP favorite wins (16.7% gap)
     *     scale = max(0, negative) = 0.0 (no change, highly expected)
     *   Larger gaps = more expected = smaller changes (approaching zero)
     */
    private fun calculateScale(
        isUpset: Boolean,
        normalizedGap: BigDecimal,
        thresholdPct: BigDecimal,
        upsetMultiplier: BigDecimal,
    ): BigDecimal =
        if (isUpset) {
            normalizedGap.divideBy(divisor = thresholdPct) * upsetMultiplier
        } else {
            (thresholdPct - normalizedGap).divideBy(divisor = thresholdPct).max(ZERO)
        }

    /**
     * Apply rating change with system-specific constraints.
     */
    private fun applyRatingChange(
        rating: Rating,
        change: BigDecimal,
        options: org.skopeo.model.RatingCalculationOptions,
        audit: AuditTrail,
    ): Rating =
        when (rating.system) {
            RatingSystem.NTRP -> applyNTRPChange(rating = rating, change = change, options = options, audit = audit)
            RatingSystem.UTR -> applyUTRChange(rating = rating, change = change, options = options, audit = audit)
        }

    /**
     * Apply rating change for NTRP (continuous values, range 1.0-7.0).
     *
     * Supports USTA NTRP Dynamic-style rating smoothing when enabled via options.
     * Smoothing averages the calculated new rating with the previous rating for stability.
     *
     * ## Smoothing Formula:
     * ```
     * calculated = previous + rawChange
     * smoothed = (calculated × factor) + (previous × (1 - factor))
     * ```
     *
     * This can be simplified to:
     * ```
     * smoothed = previous + (rawChange × factor)
     * ```
     *
     * ## Example (4.0 NTRP, +0.160 raw change):
     * - Factor 0.0: smoothed = 4.0 + (0.160 × 0.0) = 4.000 (no change)
     * - Factor 0.3: smoothed = 4.0 + (0.160 × 0.3) = 4.048 (conservative)
     * - Factor 0.5: smoothed = 4.0 + (0.160 × 0.5) = 4.080 (USTA style)
     * - Factor 0.7: smoothed = 4.0 + (0.160 × 0.7) = 4.112 (aggressive)
     * - Factor 1.0: smoothed = 4.0 + (0.160 × 1.0) = 4.160 (full change)
     *
     * ## Process Order:
     * 1. Calculate raw rating change using performance-based formula
     * 2. Apply smoothing (if enabled) to dampen the change
     * 3. Clamp to valid NTRP range (1.0-7.0)
     *
     * Note: Zero-sum property is maintained through step 2, but may break at step 3
     * if boundary clamping affects one player differently than the other.
     */
    private fun applyNTRPChange(
        rating: Rating,
        change: BigDecimal,
        options: org.skopeo.model.RatingCalculationOptions,
        audit: AuditTrail,
    ): Rating {
        val originalValue = rating.value.bd
        val calculatedValue = originalValue + change

        // Apply smoothing if enabled (USTA NTRP Dynamic style)
        // Formula: smoothed = (calculated × factor) + (previous × (1 - factor))
        // This reduces rating volatility by blending old and new ratings
        val smoothedValue =
            if (options.smoothingEnabled) {
                val factor = options.smoothingFactor.bd
                (calculatedValue * factor) + (originalValue * (ONE - factor))
            } else {
                calculatedValue
            }

        // Clamp to valid NTRP range (no rounding - keep 6 decimal precision)
        val clamped =
            if (smoothedValue < NTRP_MIN) {
                NTRP_MIN
            } else if (smoothedValue > NTRP_MAX) {
                NTRP_MAX
            } else {
                smoothedValue
            }

        audit.add(
            AuditEntry(
                message =
                    buildString {
                        append("NTRP change: ${rating.value} + ${change.toStringPrecise()} = ")
                        append("${calculatedValue.toStringPrecise()}")
                        if (options.smoothingEnabled) {
                            append(" -> smoothed ${smoothedValue.toStringPrecise()} (factor=${options.smoothingFactor})")
                        }
                        append(" -> clamped ${clamped.toStringPrecise()}")
                    },
                context =
                    mapOf(
                        "system" to "NTRP",
                        "original" to rating.value,
                        "change" to change.toStringPrecise(),
                        "newValue" to calculatedValue.toStringPrecise(),
                        "clamped" to clamped.toStringPrecise(),
                        "smoothingEnabled" to options.smoothingEnabled.toString(),
                        "smoothingFactor" to options.smoothingFactor,
                        "smoothed" to if (options.smoothingEnabled) smoothedValue.toStringPrecise() else "N/A",
                    ),
            ),
        )

        return Rating.fromValue(
            value = clamped.toStringPrecise(),
            system = rating.system,
        )
    }

    /**
     * Apply rating change for UTR (decimal values allowed, minimum 1.0).
     *
     * Supports USTA NTRP Dynamic-style rating smoothing when enabled via options.
     * Smoothing averages the calculated new rating with the previous rating for stability.
     *
     * ## UTR Smoothing Behavior:
     * UTR uses the same smoothing formula as NTRP, but with 2.5× larger K-factor:
     * - K_NTRP = 0.16
     * - K_UTR = 0.40 (2.5× larger due to wider rating range: 15.0 vs 6.0)
     *
     * This means UTR ratings change 2.5× faster than NTRP, even with smoothing:
     * ```
     * UTR_change = NTRP_change × 2.5
     * ```
     *
     * ## Example (10.0 UTR, +0.400 raw change):
     * - Factor 0.0: smoothed = 10.0 + (0.400 × 0.0) = 10.000
     * - Factor 0.3: smoothed = 10.0 + (0.400 × 0.3) = 10.120
     * - Factor 0.5: smoothed = 10.0 + (0.400 × 0.5) = 10.200
     * - Factor 0.7: smoothed = 10.0 + (0.400 × 0.7) = 10.280
     * - Factor 1.0: smoothed = 10.0 + (0.400 × 1.0) = 10.400
     *
     * Compare to equivalent NTRP (4.0, +0.160 raw change):
     * - Factor 0.5: NTRP = 4.080, UTR = 10.200
     * - Ratio: 10.200 / 4.080 ≈ 2.5× (but both use same % of range)
     *
     * ## Process Order:
     * 1. Calculate raw rating change (2.5× larger than NTRP)
     * 2. Apply smoothing (if enabled)
     * 3. Clamp to valid UTR range (1.0-16.0)
     */
    private fun applyUTRChange(
        rating: Rating,
        change: BigDecimal,
        options: org.skopeo.model.RatingCalculationOptions,
        audit: AuditTrail,
    ): Rating {
        val originalValue = rating.value.bd
        val calculatedValue = originalValue + change

        // Apply smoothing if enabled (USTA NTRP Dynamic style)
        // UTR uses same formula as NTRP but with 2.5× larger changes
        val smoothedValue =
            if (options.smoothingEnabled) {
                val factor = options.smoothingFactor.bd
                (calculatedValue * factor) + (originalValue * (ONE - factor))
            } else {
                calculatedValue
            }

        // Clamp to valid UTR range (no rounding - keep 6 decimal precision)
        val clamped =
            if (smoothedValue < UTR_MIN) {
                UTR_MIN
            } else if (smoothedValue > UTR_MAX) {
                UTR_MAX
            } else {
                smoothedValue
            }

        audit.add(
            AuditEntry(
                message =
                    buildString {
                        append("UTR change: ${rating.value} + ${change.toStringPrecise()} = ")
                        append("${calculatedValue.toStringPrecise()}")
                        if (options.smoothingEnabled) {
                            append(" -> smoothed ${smoothedValue.toStringPrecise()} (factor=${options.smoothingFactor})")
                        }
                        append(" -> clamped ${clamped.toStringPrecise()}")
                    },
                context =
                    mapOf(
                        "system" to "UTR",
                        "original" to rating.value,
                        "change" to change.toStringPrecise(),
                        "newValue" to calculatedValue.toStringPrecise(),
                        "clamped" to clamped.toStringPrecise(),
                        "smoothingEnabled" to options.smoothingEnabled.toString(),
                        "smoothingFactor" to options.smoothingFactor,
                        "smoothed" to if (options.smoothingEnabled) smoothedValue.toStringPrecise() else "N/A",
                    ),
            ),
        )

        return Rating.fromValue(
            value = clamped.toStringPrecise(),
            system = rating.system,
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
