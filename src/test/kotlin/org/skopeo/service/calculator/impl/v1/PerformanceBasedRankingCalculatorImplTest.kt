package org.skopeo.service.calculator.impl.v1

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.skopeo.dto.RankingCalculationRequest
import org.skopeo.model.MatchScore
import org.skopeo.model.PlayerProfile
import org.skopeo.model.Rating
import org.skopeo.model.RatingCalculationOptions
import org.skopeo.model.RatingSystem
import org.skopeo.model.SetScore
import org.skopeo.model.Team
import org.skopeo.model.TeamType
import java.util.stream.Stream
import kotlin.math.abs

/**
 * Comprehensive test suite for PerformanceBasedRankingCalculatorImpl.
 *
 * Rating Delta Tests (Parameterized):
 * - Uses parameterized tests for easy verification of expected deltas
 * - All test data defined in testScenarios() method for easy review and tuning
 * - Covers: expected wins, upsets, competitive matches, equal players, various score margins
 *
 * Audit Trail Tests (Nested):
 * - Verifies audit trail structure and content
 * - Tests calculation start, match result, ranking adjustments, rating changes
 * - Validates zero-sum property and audit entry order
 * - Covers both NTRP and UTR rating systems
 */
class PerformanceBasedRankingCalculatorImplTest {
    private val calculator = PerformanceBasedRankingCalculatorImpl()

    companion object {
        /**
         * NTRP test scenarios from shared TestScenarios.
         *
         * Format: scenario, p1Rating, p2Rating, p1Games, p2Games, winner, expectedP1Delta, expectedP2Delta, description
         */
        @JvmStatic
        fun ntrpScenarios(): Stream<Arguments> =
            TestScenarios.allScenarios.stream().map { scenario ->
                Arguments.of(
                    scenario.id,
                    scenario.ntrpP1,
                    scenario.ntrpP2,
                    scenario.p1Games,
                    scenario.p2Games,
                    scenario.winner,
                    scenario.expectedNtrpP1Delta,
                    scenario.expectedNtrpP2Delta,
                    scenario.description,
                )
            }

        /**
         * UTR test scenarios from shared TestScenarios.
         *
         * Format: scenario, p1Rating, p2Rating, p1Games, p2Games, winner, expectedP1Delta, expectedP2Delta, description
         */
        @JvmStatic
        fun utrScenarios(): Stream<Arguments> =
            TestScenarios.allScenarios.stream().map { scenario ->
                Arguments.of(
                    scenario.id,
                    scenario.utrP1,
                    scenario.utrP2,
                    scenario.p1Games,
                    scenario.p2Games,
                    scenario.winner,
                    scenario.expectedUtrP1Delta,
                    scenario.expectedUtrP2Delta,
                    scenario.description,
                )
            }
    }

    private fun createRequest(
        p1Rating: String,
        p2Rating: String,
        p1Games: Int,
        p2Games: Int,
        // "P1"/"P2" (player ID) or "T1"/"T2" (team ID)
        winner: String,
        smoothingEnabled: Boolean = false,
        smoothingFactor: Double = 0.5,
    ): RankingCalculationRequest {
        // Map player ID to team ID (pass through if already a team ID)
        val teamWinner =
            when (winner) {
                "P1" -> "T1"
                "P2" -> "T2"
                else -> winner // Already a team ID
            }

        return createSinglesRequest(
            p1Rating = p1Rating,
            p2Rating = p2Rating,
            system = RatingSystem.NTRP,
            p1Games = p1Games,
            p2Games = p2Games,
            winner = teamWinner,
            smoothingEnabled = smoothingEnabled,
            smoothingFactor = smoothingFactor,
        )
    }

    private fun createUTRRequest(
        p1Rating: String,
        p2Rating: String,
        p1Games: Int,
        p2Games: Int,
        // "P1"/"P2" (player ID) or "T1"/"T2" (team ID)
        winner: String,
        smoothingEnabled: Boolean = false,
        smoothingFactor: Double = 0.5,
    ): RankingCalculationRequest {
        // Map player ID to team ID (pass through if already a team ID)
        val teamWinner =
            when (winner) {
                "P1" -> "T1"
                "P2" -> "T2"
                else -> winner // Already a team ID
            }

        return createSinglesRequest(
            p1Rating = p1Rating,
            p2Rating = p2Rating,
            system = RatingSystem.UTR,
            p1Games = p1Games,
            p2Games = p2Games,
            winner = teamWinner,
            smoothingEnabled = smoothingEnabled,
            smoothingFactor = smoothingFactor,
        )
    }

    @ParameterizedTest(name = "{0}: {8}")
    @MethodSource("ntrpScenarios")
    @DisplayName("NTRP rating delta calculations")
    fun testNTRPRatingDeltas(
        scenario: String,
        p1Rating: String,
        p2Rating: String,
        p1Games: Int,
        p2Games: Int,
        winner: String,
        expectedP1Delta: String,
        expectedP2Delta: String,
        description: String,
    ) {
        // Get the full scenario to access smoothing parameters
        val fullScenario = TestScenarios.allScenarios.find { it.id == scenario }!!

        val result =
            calculator.calculate(
                request =
                    createRequest(
                        p1Rating = p1Rating,
                        p2Rating = p2Rating,
                        p1Games = p1Games,
                        p2Games = p2Games,
                        winner = winner,
                        smoothingEnabled = fullScenario.smoothingEnabled,
                        smoothingFactor = fullScenario.smoothingFactor,
                    ),
            )

        result.response.ratingChanges["P1"]?.change shouldBe expectedP1Delta
        result.response.ratingChanges["P2"]?.change shouldBe expectedP2Delta
    }

    @ParameterizedTest(name = "{0}: {8}")
    @MethodSource("utrScenarios")
    @DisplayName("UTR rating delta calculations")
    fun testUTRRatingDeltas(
        scenario: String,
        p1Rating: String,
        p2Rating: String,
        p1Games: Int,
        p2Games: Int,
        winner: String,
        expectedP1Delta: String,
        expectedP2Delta: String,
        description: String,
    ) {
        // Get the full scenario to access smoothing parameters
        val fullScenario = TestScenarios.allScenarios.find { it.id == scenario }!!

        val result =
            calculator.calculate(
                request =
                    createUTRRequest(
                        p1Rating = p1Rating,
                        p2Rating = p2Rating,
                        p1Games = p1Games,
                        p2Games = p2Games,
                        winner = winner,
                        smoothingEnabled = fullScenario.smoothingEnabled,
                        smoothingFactor = fullScenario.smoothingFactor,
                    ),
            )

        result.response.ratingChanges["P1"]?.change shouldBe expectedP1Delta
        result.response.ratingChanges["P2"]?.change shouldBe expectedP2Delta
    }

    @Test
    @DisplayName("Score proportionality: 6-0 should give 5× more change than 6-4")
    fun testScoreProportionality() {
        // Use competitive gap (0.2) that's well below threshold to avoid zero deltas
        val result60 =
            calculator.calculate(
                request =
                    createRequest(
                        p1Rating = "4.5",
                        p2Rating = "4.3",
                        p1Games = 6,
                        p2Games = 0,
                        winner = "P1",
                    ),
            )
        val result64 =
            calculator.calculate(
                request =
                    createRequest(
                        p1Rating = "4.5",
                        p2Rating = "4.3",
                        p1Games = 6,
                        p2Games = 4,
                        winner = "P1",
                    ),
            )

        val delta60 = result60.response.ratingChanges["P1"]?.change?.toDouble() ?: 0.0
        val delta64 = result64.response.ratingChanges["P1"]?.change?.toDouble() ?: 0.0

        val ratio = delta60 / delta64
        ratio shouldBe (5.0 plusOrMinus 0.01)
    }

    @Test
    @DisplayName("Upset factor: upsets should give larger changes than expected wins")
    fun testUpsetFactor() {
        // Use competitive gap (0.2) to ensure both scenarios produce non-zero deltas
        val expected =
            calculator.calculate(
                request =
                    createRequest(
                        p1Rating = "4.5",
                        p2Rating = "4.3",
                        p1Games = 6,
                        p2Games = 0,
                        winner = "P1",
                    ),
            )
        val upset =
            calculator.calculate(
                request =
                    createRequest(
                        p1Rating = "4.3",
                        p2Rating = "4.5",
                        p1Games = 6,
                        p2Games = 0,
                        winner = "P1",
                    ),
            )

        val expectedDelta = expected.response.ratingChanges["P1"]?.change?.toDouble() ?: 0.0
        val upsetDelta = upset.response.ratingChanges["P1"]?.change?.toDouble() ?: 0.0

        // With normalized gaps: upset uses (gap/threshold)×2.0, expected uses (threshold-gap)/threshold
        // For gap=0.2, threshold=8.3%: normalized=3.3%
        // Expected: (8.3%-3.3%)/8.3% = 0.6
        // Upset: (3.3%/8.3%)×2.0 = 0.8
        // Ratio: 0.8/0.6 = 1.33
        upsetDelta shouldBeGreaterThan expectedDelta
        val ratio = upsetDelta / expectedDelta
        ratio shouldBe (1.33 plusOrMinus 0.05)
    }

    // ========================================
    // Audit Trail Tests
    // ========================================

    @Nested
    @DisplayName("Audit Trail")
    inner class AuditTrailTests {
        @Test
        @DisplayName("Contains calculation start with player info")
        fun testAuditTrailContainsCalculationStart() {
            val request = createAuditTestRequest()
            val result = calculator.calculate(request)

            val startEntry = result.audit.find { it.message.contains("Calculating ranking") }

            startEntry shouldNotBe null
            startEntry!!.context["player1"] shouldBe "John Doe"
            startEntry.context["player1Rating"] shouldBe "4.5"
            startEntry.context["player2"] shouldBe "Jane Smith"
            startEntry.context["player2Rating"] shouldBe "4.0"
        }

        @Test
        @DisplayName("Contains match result with dominance factors")
        fun testAuditTrailContainsMatchResult() {
            val request = createAuditTestRequest()
            val result = calculator.calculate(request)

            val matchResultEntry = result.audit.find { it.message.contains("Match result") }

            matchResultEntry shouldNotBe null
            matchResultEntry!!.context shouldContainKey "winnerId"
            matchResultEntry.context shouldContainKey "winnerDominanceFactor"
            matchResultEntry.context shouldContainKey "loserDominanceFactor"
        }

        @Test
        @DisplayName("Contains ranking adjustment calculation (zero-sum)")
        fun testAuditTrailContainsRankingAdjustment() {
            val request = createAuditTestRequest()
            val result = calculator.calculate(request)

            val rankingAdjustmentEntry = result.audit.find { it.message.contains("Ranking Adjustment Calculation") }

            rankingAdjustmentEntry shouldNotBe null
            rankingAdjustmentEntry!!.context shouldContainKey "player1RankingAdjustment"
            rankingAdjustmentEntry.context shouldContainKey "player2RankingAdjustment"

            // Ranking adjustments should be opposite (zero-sum before clamping)
            val adjustment1 = (rankingAdjustmentEntry.context["player1RankingAdjustment"] as String).toDouble()
            val adjustment2 = (rankingAdjustmentEntry.context["player2RankingAdjustment"] as String).toDouble()
            (adjustment1 + adjustment2) shouldBe (0.0 plusOrMinus 0.0001)
        }

        @Test
        @DisplayName("Contains rating changes (zero-sum)")
        fun testAuditTrailContainsRatingChanges() {
            val request = createAuditTestRequest()
            val result = calculator.calculate(request)

            val ratingChangesEntry = result.audit.find { it.message.contains("Rating changes") }

            ratingChangesEntry shouldNotBe null
            ratingChangesEntry!!.context shouldContainKey "player1Change"
            ratingChangesEntry.context shouldContainKey "player2Change"

            // Changes should be opposite (zero-sum)
            val change1 = (ratingChangesEntry.context["player1Change"] as String).toDouble()
            val change2 = (ratingChangesEntry.context["player2Change"] as String).toDouble()
            (change1 + change2) shouldBe (0.0 plusOrMinus 0.0001)
        }

        @Test
        @DisplayName("Contains NTRP system-specific changes")
        fun testAuditTrailContainsSystemSpecificChanges() {
            val request = createAuditTestRequest()
            val result = calculator.calculate(request)

            val ntrpChanges = result.audit.filter { it.message.contains("NTRP change") }

            ntrpChanges.size shouldBe 2

            ntrpChanges.forEach { entry ->
                entry.context["system"] shouldBe "NTRP"
                entry.context shouldContainKey "original"
                entry.context shouldContainKey "change"
                entry.context shouldContainKey "newValue"
                entry.context shouldContainKey "clamped"
            }
        }

        @Test
        @DisplayName("Contains UTR system-specific changes")
        fun testAuditTrailForUTRMatch() {
            val request = createUTRAuditTestRequest()
            val result = calculator.calculate(request)

            val utrChanges = result.audit.filter { it.message.contains("UTR change") }

            utrChanges.size shouldBe 2

            utrChanges.forEach { entry ->
                entry.context["system"] shouldBe "UTR"
                entry.context shouldContainKey "original"
                entry.context shouldContainKey "change"
            }
        }

        @Test
        @DisplayName("Audit entries are in correct order")
        fun testAuditTrailOrder() {
            val request = createAuditTestRequest()
            val result = calculator.calculate(request)

            val messages = result.audit.map { it.message }

            val calculatingIndex = messages.indexOfFirst { it.contains("Calculating ranking") }
            val matchResultIndex = messages.indexOfFirst { it.contains("Match result") }
            val rankingAdjustmentIndex = messages.indexOfFirst { it.contains("Ranking Adjustment Calculation") }
            val ratingChangesIndex = messages.indexOfFirst { it.contains("Rating changes") }

            calculatingIndex shouldBeGreaterThanOrEqualTo 0
            matchResultIndex shouldBeGreaterThanOrEqualTo 0
            rankingAdjustmentIndex shouldBeGreaterThanOrEqualTo 0
            ratingChangesIndex shouldBeGreaterThanOrEqualTo 0

            // Verify order
            calculatingIndex shouldBeLessThan matchResultIndex
            matchResultIndex shouldBeLessThan rankingAdjustmentIndex
            rankingAdjustmentIndex shouldBeLessThan ratingChangesIndex
        }

        @Test
        @DisplayName("Audit context data is valid")
        fun testAuditContextData() {
            val request = createAuditTestRequest()
            val result = calculator.calculate(request)

            result.audit.forEach { entry ->
                entry.context.values.forEach { value ->
                    (value is String || value is Number) shouldBe true
                }
            }
        }

        @Test
        @DisplayName("Contains adjustment factors for both players")
        fun testAuditTrailContainsAdjustmentFactors() {
            val request = createAuditTestRequest()
            val result = calculator.calculate(request)

            val factorEntries = result.audit.filter { it.message.contains("Adjustment factors") }

            factorEntries.size shouldBe 2

            factorEntries.forEach { entry ->
                entry.context shouldContainKey "playerId"
                entry.context shouldContainKey "kFactor"
                entry.context shouldContainKey "dominance"
                entry.context shouldContainKey "ratingGap"
                entry.context shouldContainKey "normalizedGap"
                entry.context shouldContainKey "competitiveThresholdPct"
                entry.context shouldContainKey "isUpset"
                entry.context shouldContainKey "upsetMultiplier"
                entry.context shouldContainKey "scale"
                entry.context shouldContainKey "sign"
                entry.context shouldContainKey "change"
            }
        }

        @Test
        @DisplayName("Adjustment factors reproduce the master formula: change = K × |dominance| × scale × sign")
        fun testAdjustmentFactorsReproduceChange() {
            val request = createAuditTestRequest()
            val result = calculator.calculate(request)

            val factorEntries = result.audit.filter { it.message.contains("Adjustment factors") }

            factorEntries.forEach { entry ->
                val k = (entry.context["kFactor"] as String).toDouble()
                val dominance = (entry.context["dominance"] as String).toDouble()
                val scale = (entry.context["scale"] as String).toDouble()
                val sign = if (entry.context["sign"] == "+1") 1.0 else -1.0
                val change = (entry.context["change"] as String).toDouble()

                (k * abs(dominance) * scale * sign) shouldBe (change plusOrMinus 0.000001)
            }
        }

        @Test
        @DisplayName("Adjustment factors for expected win: no upset, zero scale beyond threshold")
        fun testAdjustmentFactorsForExpectedWin() {
            // 4.5 vs 4.0 NTRP favorite wins 6-4, 6-3: gap normalizes just past the threshold → scale 0
            val request = createAuditTestRequest()
            val result = calculator.calculate(request)

            val winnerEntry =
                result.audit.first { it.message.contains("Adjustment factors") && it.context["sign"] == "+1" }

            winnerEntry.context["playerId"] shouldBe "P123"
            winnerEntry.context["kFactor"] shouldBe "0.160000"
            winnerEntry.context["dominance"] shouldBe "0.266667"
            winnerEntry.context["ratingGap"] shouldBe "0.500000"
            winnerEntry.context["normalizedGap"] shouldBe "0.083333"
            winnerEntry.context["competitiveThresholdPct"] shouldBe "0.083000"
            winnerEntry.context["isUpset"] shouldBe "false"
            winnerEntry.context["scale"] shouldBe "0.000000"
            winnerEntry.context["change"] shouldBe "0.000000"
        }

        @Test
        @DisplayName("Adjustment factors for an upset: upset flag and multiplier applied")
        fun testAdjustmentFactorsForUpset() {
            // 2.5 underdog beats 3.5 favorite 6-0 (1.0 gap)
            val request =
                createSinglesRequest(
                    p1Rating = "2.5",
                    p2Rating = "3.5",
                    system = RatingSystem.NTRP,
                    p1Games = 6,
                    p2Games = 0,
                    winner = "T1",
                )
            val result = calculator.calculate(request)

            val factorEntries = result.audit.filter { it.message.contains("Adjustment factors") }

            factorEntries.size shouldBe 2

            factorEntries.forEach { entry ->
                entry.context["isUpset"] shouldBe "true"
                entry.context["upsetMultiplier"] shouldBe "2.000000"
                entry.context["normalizedGap"] shouldBe "0.166667"
                entry.context["scale"] shouldBe "4.016072"
            }

            val winnerEntry = factorEntries.first { it.context["sign"] == "+1" }
            winnerEntry.context["playerId"] shouldBe "P1"
            winnerEntry.context["dominance"] shouldBe "1.000000"
            winnerEntry.context["change"] shouldBe "0.642572"

            val loserEntry = factorEntries.first { it.context["sign"] == "-1" }
            loserEntry.context["dominance"] shouldBe "-1.000000"
            loserEntry.context["change"] shouldBe "-0.642572"
        }
    }

    // ========================================
    // Rating Smoothing Tests
    // ========================================

    @Nested
    @DisplayName("Rating Smoothing Tests")
    inner class RatingSmoothingTests {
        @Nested
        @DisplayName("NTRP Smoothing")
        inner class NTRPSmoothing {
            @Test
            @DisplayName("Without smoothing applies full change")
            fun testWithoutSmoothing() {
                val request =
                    createNTRPRequest(
                        p1Rating = "4.0",
                        p2Rating = "4.0",
                        p1Games = 6,
                        p2Games = 0,
                        options = RatingCalculationOptions(smoothingEnabled = false),
                    )

                val result = calculator.calculate(request = request)

                val p1NewRating = result.response.ratingChanges["P1"]!!.newRating.value.toDouble()
                val p1OldRating = 4.0
                val p1Change = result.response.ratingChanges["P1"]!!.change.toDouble()

                // Without smoothing: new = old + change
                p1NewRating shouldBe (p1OldRating + p1Change)
            }

            @Test
            @DisplayName("With smoothing (0.5) applies half change")
            fun testWithSmoothingHalf() {
                // Run without smoothing first to know the full change
                val requestNoSmoothing =
                    createNTRPRequest(
                        p1Rating = "4.0",
                        p2Rating = "4.0",
                        p1Games = 6,
                        p2Games = 0,
                        options = RatingCalculationOptions(smoothingEnabled = false),
                    )
                val resultNoSmoothing = calculator.calculate(request = requestNoSmoothing)
                val fullChange = resultNoSmoothing.response.ratingChanges["P1"]!!.change.toDouble()

                // Now run with smoothing
                val requestWithSmoothing =
                    createNTRPRequest(
                        p1Rating = "4.0",
                        p2Rating = "4.0",
                        p1Games = 6,
                        p2Games = 0,
                        options = RatingCalculationOptions(smoothingEnabled = true, smoothingFactor = 0.5),
                    )
                val resultWithSmoothing = calculator.calculate(request = requestWithSmoothing)
                val smoothedChange = resultWithSmoothing.response.ratingChanges["P1"]!!.change.toDouble()

                // With smoothing factor 0.5: change should be approximately half
                smoothedChange.shouldBeBetween(fullChange * 0.45, fullChange * 0.55, 0.0)
            }

            @Test
            @DisplayName("With aggressive smoothing (0.7) applies 70% of change")
            fun testWithAggressiveSmoothing() {
                val requestNoSmoothing =
                    createNTRPRequest(
                        p1Rating = "4.0",
                        p2Rating = "4.0",
                        p1Games = 6,
                        p2Games = 0,
                        options = RatingCalculationOptions(smoothingEnabled = false),
                    )
                val resultNoSmoothing = calculator.calculate(request = requestNoSmoothing)
                val fullChange = resultNoSmoothing.response.ratingChanges["P1"]!!.change.toDouble()

                val requestWithSmoothing =
                    createNTRPRequest(
                        p1Rating = "4.0",
                        p2Rating = "4.0",
                        p1Games = 6,
                        p2Games = 0,
                        options = RatingCalculationOptions(smoothingEnabled = true, smoothingFactor = 0.7),
                    )
                val resultWithSmoothing = calculator.calculate(request = requestWithSmoothing)
                val smoothedChange = resultWithSmoothing.response.ratingChanges["P1"]!!.change.toDouble()

                // Should be approximately 70% of full change
                smoothedChange.shouldBeBetween(fullChange * 0.65, fullChange * 0.75, 0.0)
            }

            @Test
            @DisplayName("With conservative smoothing (0.3) applies 30% of change")
            fun testWithConservativeSmoothing() {
                val requestNoSmoothing =
                    createNTRPRequest(
                        p1Rating = "4.0",
                        p2Rating = "4.0",
                        p1Games = 6,
                        p2Games = 0,
                        options = RatingCalculationOptions(smoothingEnabled = false),
                    )
                val resultNoSmoothing = calculator.calculate(request = requestNoSmoothing)
                val fullChange = resultNoSmoothing.response.ratingChanges["P1"]!!.change.toDouble()

                val requestWithSmoothing =
                    createNTRPRequest(
                        p1Rating = "4.0",
                        p2Rating = "4.0",
                        p1Games = 6,
                        p2Games = 0,
                        options = RatingCalculationOptions(smoothingEnabled = true, smoothingFactor = 0.3),
                    )
                val resultWithSmoothing = calculator.calculate(request = requestWithSmoothing)
                val smoothedChange = resultWithSmoothing.response.ratingChanges["P1"]!!.change.toDouble()

                // Should be approximately 30% of full change
                smoothedChange.shouldBeBetween(fullChange * 0.25, fullChange * 0.35, 0.0)
            }

            @Test
            @DisplayName("Smoothing preserves zero-sum property before clamping")
            fun testSmoothingPreservesZeroSum() {
                val request =
                    createNTRPRequest(
                        p1Rating = "4.0",
                        p2Rating = "4.0",
                        p1Games = 6,
                        p2Games = 4,
                        options = RatingCalculationOptions(smoothingEnabled = true, smoothingFactor = 0.5),
                    )

                val result = calculator.calculate(request = request)

                val p1Change = result.response.ratingChanges["P1"]!!.change.toDouble()
                val p2Change = result.response.ratingChanges["P2"]!!.change.toDouble()

                // Should still be zero-sum (within floating point precision)
                (p1Change + p2Change).shouldBeBetween(-0.000001, 0.000001, 0.0)
            }

            @Test
            @DisplayName("Audit trail includes smoothing information")
            fun testAuditTrailIncludesSmoothing() {
                val request =
                    createNTRPRequest(
                        p1Rating = "4.0",
                        p2Rating = "4.0",
                        p1Games = 6,
                        p2Games = 0,
                        options = RatingCalculationOptions(smoothingEnabled = true, smoothingFactor = 0.5),
                    )

                val result = calculator.calculate(request = request)

                val ntrpChanges = result.audit.filter { it.message.contains("NTRP change") }

                ntrpChanges.forEach { entry ->
                    // Should have smoothing context
                    entry.context shouldContainKey "smoothingEnabled"
                    entry.context shouldContainKey "smoothingFactor"
                    entry.context shouldContainKey "smoothed"

                    // Should show smoothing is enabled
                    entry.context["smoothingEnabled"] shouldBe "true"
                    entry.context["smoothingFactor"] shouldBe 0.5

                    // Message should mention smoothing
                    entry.message.contains("smoothed") shouldBe true
                }
            }
        }

        @Nested
        @DisplayName("UTR Smoothing")
        inner class UTRSmoothing {
            @Test
            @DisplayName("Without smoothing applies full change")
            fun testWithoutSmoothing() {
                val request =
                    createUTRRequest(
                        p1Rating = "10.0",
                        p2Rating = "10.0",
                        p1Games = 6,
                        p2Games = 0,
                        options = RatingCalculationOptions(smoothingEnabled = false),
                    )

                val result = calculator.calculate(request = request)

                val p1NewRating = result.response.ratingChanges["P1"]!!.newRating.value.toDouble()
                val p1OldRating = 10.0
                val p1Change = result.response.ratingChanges["P1"]!!.change.toDouble()

                // Without smoothing: new = old + change
                p1NewRating shouldBe (p1OldRating + p1Change)
            }

            @Test
            @DisplayName("With smoothing (0.5) applies half change")
            fun testWithSmoothingHalf() {
                val requestNoSmoothing =
                    createUTRRequest(
                        p1Rating = "10.0",
                        p2Rating = "10.0",
                        p1Games = 6,
                        p2Games = 0,
                        options = RatingCalculationOptions(smoothingEnabled = false),
                    )
                val resultNoSmoothing = calculator.calculate(request = requestNoSmoothing)
                val fullChange = resultNoSmoothing.response.ratingChanges["P1"]!!.change.toDouble()

                val requestWithSmoothing =
                    createUTRRequest(
                        p1Rating = "10.0",
                        p2Rating = "10.0",
                        p1Games = 6,
                        p2Games = 0,
                        options = RatingCalculationOptions(smoothingEnabled = true, smoothingFactor = 0.5),
                    )
                val resultWithSmoothing = calculator.calculate(request = requestWithSmoothing)
                val smoothedChange = resultWithSmoothing.response.ratingChanges["P1"]!!.change.toDouble()

                // With smoothing factor 0.5: change should be approximately half
                smoothedChange.shouldBeBetween(fullChange * 0.45, fullChange * 0.55, 0.0)
            }

            @Test
            @DisplayName("UTR scaling preserved with smoothing")
            fun testUTRScalingPreserved() {
                // Test that UTR changes are still 2.5× NTRP changes even with smoothing
                val ntrpRequest =
                    createNTRPRequest(
                        p1Rating = "4.0",
                        p2Rating = "4.0",
                        p1Games = 6,
                        p2Games = 0,
                        options = RatingCalculationOptions(smoothingEnabled = true, smoothingFactor = 0.5),
                    )
                val ntrpResult = calculator.calculate(request = ntrpRequest)
                val ntrpChange = ntrpResult.response.ratingChanges["P1"]!!.change.toDouble()

                val utrRequest =
                    createUTRRequest(
                        p1Rating = "10.0",
                        p2Rating = "10.0",
                        p1Games = 6,
                        p2Games = 0,
                        options = RatingCalculationOptions(smoothingEnabled = true, smoothingFactor = 0.5),
                    )
                val utrResult = calculator.calculate(request = utrRequest)
                val utrChange = utrResult.response.ratingChanges["P1"]!!.change.toDouble()

                // UTR should be approximately 2.5× NTRP
                val ratio = utrChange / ntrpChange
                ratio.shouldBeBetween(2.45, 2.55, 0.0)
            }
        }

        @Nested
        @DisplayName("Edge Cases")
        inner class EdgeCases {
            @Test
            @DisplayName("Smoothing works at boundary (7.0 NTRP)")
            fun testSmoothingAtMaxBoundary() {
                val request =
                    createNTRPRequest(
                        p1Rating = "6.8",
                        p2Rating = "4.0",
                        p1Games = 6,
                        p2Games = 0,
                        options = RatingCalculationOptions(smoothingEnabled = true, smoothingFactor = 0.5),
                    )

                val result = calculator.calculate(request = request)

                val p1NewRating = result.response.ratingChanges["P1"]!!.newRating.value.toDouble()

                // Should not exceed 7.0 even with smoothing
                p1NewRating shouldBeLessThanOrEqualTo 7.0
            }

            @Test
            @DisplayName("Smoothing works at boundary (1.0 NTRP)")
            fun testSmoothingAtMinBoundary() {
                val request =
                    createNTRPRequest(
                        p1Rating = "4.0",
                        p2Rating = "1.2",
                        p1Games = 0,
                        p2Games = 6,
                        options = RatingCalculationOptions(smoothingEnabled = true, smoothingFactor = 0.5),
                    )

                val result = calculator.calculate(request = request)

                val p1NewRating = result.response.ratingChanges["P1"]!!.newRating.value.toDouble()

                // Should not go below 1.0 even with smoothing
                p1NewRating shouldBeGreaterThanOrEqualTo 1.0
            }

            @Test
            @DisplayName("Smoothing factor 1.0 equals no smoothing")
            fun testSmoothingFactorOne() {
                val requestNoSmoothing =
                    createNTRPRequest(
                        p1Rating = "4.0",
                        p2Rating = "4.0",
                        p1Games = 6,
                        p2Games = 0,
                        options = RatingCalculationOptions(smoothingEnabled = false),
                    )
                val resultNoSmoothing = calculator.calculate(request = requestNoSmoothing)

                val requestFullSmoothing =
                    createNTRPRequest(
                        p1Rating = "4.0",
                        p2Rating = "4.0",
                        p1Games = 6,
                        p2Games = 0,
                        options = RatingCalculationOptions(smoothingEnabled = true, smoothingFactor = 1.0),
                    )
                val resultFullSmoothing = calculator.calculate(request = requestFullSmoothing)

                // Should produce identical results
                val changeNoSmoothing = resultNoSmoothing.response.ratingChanges["P1"]!!.change
                val changeFullSmoothing = resultFullSmoothing.response.ratingChanges["P1"]!!.change

                changeNoSmoothing shouldBe changeFullSmoothing
            }
        }

        // Helper methods for smoothing tests
        private fun createNTRPRequest(
            p1Rating: String,
            p2Rating: String,
            p1Games: Int,
            p2Games: Int,
            options: RatingCalculationOptions? = null,
        ): RankingCalculationRequest {
            val winner = if (p1Games > p2Games) "T1" else "T2"
            return createSinglesRequest(
                p1Rating = p1Rating,
                p2Rating = p2Rating,
                system = RatingSystem.NTRP,
                p1Games = p1Games,
                p2Games = p2Games,
                winner = winner,
                smoothingEnabled = options?.smoothingEnabled ?: false,
                smoothingFactor = options?.smoothingFactor ?: 0.5,
            )
        }

        private fun createUTRRequest(
            p1Rating: String,
            p2Rating: String,
            p1Games: Int,
            p2Games: Int,
            options: RatingCalculationOptions? = null,
        ): RankingCalculationRequest {
            val winner = if (p1Games > p2Games) "T1" else "T2"
            return createSinglesRequest(
                p1Rating = p1Rating,
                p2Rating = p2Rating,
                system = RatingSystem.UTR,
                p1Games = p1Games,
                p2Games = p2Games,
                winner = winner,
                smoothingEnabled = options?.smoothingEnabled ?: false,
                smoothingFactor = options?.smoothingFactor ?: 0.5,
            )
        }
    }

    // ========================================
    // Helper Methods
    // ========================================

    private fun createAuditTestRequest(): RankingCalculationRequest {
        val player1 =
            PlayerProfile(
                playerId = "P123",
                name = "John Doe",
                rating = Rating.fromValue(value = "4.5", system = RatingSystem.NTRP),
            )

        val player2 =
            PlayerProfile(
                playerId = "P456",
                name = "Jane Smith",
                rating = Rating.fromValue(value = "4.0", system = RatingSystem.NTRP),
            )

        val team1 =
            Team(
                teamId = "T1",
                name = player1.name,
                players = listOf(player1),
                teamType = TeamType.SINGLES,
            )

        val team2 =
            Team(
                teamId = "T2",
                name = player2.name,
                players = listOf(player2),
                teamType = TeamType.SINGLES,
            )

        val sets =
            listOf(
                SetScore(
                    games = mapOf("T1" to 6, "T2" to 4),
                    winner = "T1",
                ),
                SetScore(
                    games = mapOf("T1" to 6, "T2" to 3),
                    winner = "T1",
                ),
            )

        return RankingCalculationRequest(
            teams = mapOf("T1" to team1, "T2" to team2),
            matchScore = MatchScore(sets = sets),
        )
    }

    private fun createUTRAuditTestRequest(): RankingCalculationRequest {
        val player1 =
            PlayerProfile(
                playerId = "P789",
                name = "Mike Wilson",
                rating = Rating.fromValue(value = "8.5", system = RatingSystem.UTR),
            )

        val player2 =
            PlayerProfile(
                playerId = "P101",
                name = "Sarah Lee",
                rating = Rating.fromValue(value = "8.2", system = RatingSystem.UTR),
            )

        val team1 =
            Team(
                teamId = "T1",
                name = player1.name,
                players = listOf(player1),
                teamType = TeamType.SINGLES,
            )

        val team2 =
            Team(
                teamId = "T2",
                name = player2.name,
                players = listOf(player2),
                teamType = TeamType.SINGLES,
            )

        val sets =
            listOf(
                SetScore(
                    games = mapOf("T1" to 6, "T2" to 4),
                    winner = "T1",
                ),
            )

        return RankingCalculationRequest(
            teams = mapOf("T1" to team1, "T2" to team2),
            matchScore = MatchScore(sets = sets),
        )
    }
}
