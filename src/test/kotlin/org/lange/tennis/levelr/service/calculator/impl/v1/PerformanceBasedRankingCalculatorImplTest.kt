package org.lange.tennis.levelr.service.calculator.impl.v1

import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.lange.tennis.levelr.dto.RankingCalculationRequest
import org.lange.tennis.levelr.model.MatchScore
import org.lange.tennis.levelr.model.PlayerProfile
import org.lange.tennis.levelr.model.Rating
import org.lange.tennis.levelr.model.RatingCalculationOptions
import org.lange.tennis.levelr.model.RatingSystem
import org.lange.tennis.levelr.model.SetScore
import java.util.stream.Stream

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
        winner: String,
        smoothingEnabled: Boolean = false,
        smoothingFactor: Double = 0.5,
    ): RankingCalculationRequest {
        val options =
            if (smoothingEnabled) {
                RatingCalculationOptions(smoothingEnabled = true, smoothingFactor = smoothingFactor)
            } else {
                null
            }

        return RankingCalculationRequest(
            players =
                mapOf(
                    "P1" to
                        PlayerProfile(
                            playerId = "P1",
                            name = "Player 1",
                            rating = Rating(value = p1Rating, system = RatingSystem.NTRP),
                        ),
                    "P2" to
                        PlayerProfile(
                            playerId = "P2",
                            name = "Player 2",
                            rating = Rating(value = p2Rating, system = RatingSystem.NTRP),
                        ),
                ),
            matchScore =
                MatchScore(
                    sets =
                        listOf(
                            SetScore(
                                games = mapOf("P1" to p1Games, "P2" to p2Games),
                                winner = winner,
                            ),
                        ),
                ),
            options = options,
        )
    }

    private fun createUTRRequest(
        p1Rating: String,
        p2Rating: String,
        p1Games: Int,
        p2Games: Int,
        winner: String,
        smoothingEnabled: Boolean = false,
        smoothingFactor: Double = 0.5,
    ): RankingCalculationRequest {
        val options =
            if (smoothingEnabled) {
                RatingCalculationOptions(smoothingEnabled = true, smoothingFactor = smoothingFactor)
            } else {
                null
            }

        return RankingCalculationRequest(
            players =
                mapOf(
                    "P1" to
                        PlayerProfile(
                            playerId = "P1",
                            name = "Player 1",
                            rating = Rating(value = p1Rating, system = RatingSystem.UTR),
                        ),
                    "P2" to
                        PlayerProfile(
                            playerId = "P2",
                            name = "Player 2",
                            rating = Rating(value = p2Rating, system = RatingSystem.UTR),
                        ),
                ),
            matchScore =
                MatchScore(
                    sets =
                        listOf(
                            SetScore(
                                games = mapOf("P1" to p1Games, "P2" to p2Games),
                                winner = winner,
                            ),
                        ),
                ),
            options = options,
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

        assertEquals(
            expectedP1Delta,
            result.response.ratingChanges["P1"]?.change,
            "$scenario - P1 delta mismatch: $description",
        )
        assertEquals(
            expectedP2Delta,
            result.response.ratingChanges["P2"]?.change,
            "$scenario - P2 delta mismatch: $description",
        )
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

        assertEquals(
            expectedP1Delta,
            result.response.ratingChanges["P1"]?.change,
            "$scenario - P1 delta mismatch: $description",
        )
        assertEquals(
            expectedP2Delta,
            result.response.ratingChanges["P2"]?.change,
            "$scenario - P2 delta mismatch: $description",
        )
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
        assertEquals(5.0, ratio, 0.01, "6-0 should give 5× more change than 6-4")
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
        assertTrue(upsetDelta > expectedDelta, "Upset should give more change than expected win")
        val ratio = upsetDelta / expectedDelta
        assertEquals(1.33, ratio, 0.05, "Upset/Expected ratio with normalized gaps")
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

            assertTrue(startEntry != null, "Audit should contain calculation start entry")
            assertEquals("John Doe", startEntry!!.context["player1"])
            assertEquals("4.5", startEntry!!.context["player1Rating"])
            assertEquals("Jane Smith", startEntry!!.context["player2"])
            assertEquals("4.0", startEntry!!.context["player2Rating"])
        }

        @Test
        @DisplayName("Contains match result with dominance factors")
        fun testAuditTrailContainsMatchResult() {
            val request = createAuditTestRequest()
            val result = calculator.calculate(request)

            val matchResultEntry = result.audit.find { it.message.contains("Match result") }

            assertTrue(matchResultEntry != null, "Audit should contain match result entry")
            assertTrue(matchResultEntry!!.context.containsKey("winnerId"))
            assertTrue(matchResultEntry!!.context.containsKey("winnerDominanceFactor"))
            assertTrue(matchResultEntry!!.context.containsKey("loserDominanceFactor"))
        }

        @Test
        @DisplayName("Contains ranking adjustment calculation (zero-sum)")
        fun testAuditTrailContainsRankingAdjustment() {
            val request = createAuditTestRequest()
            val result = calculator.calculate(request)

            val rankingAdjustmentEntry = result.audit.find { it.message.contains("Ranking Adjustment Calculation") }

            assertTrue(rankingAdjustmentEntry != null, "Audit should contain ranking adjustment entry")
            assertTrue(rankingAdjustmentEntry!!.context.containsKey("player1RankingAdjustment"))
            assertTrue(rankingAdjustmentEntry!!.context.containsKey("player2RankingAdjustment"))

            // Ranking adjustments should be opposite (zero-sum before clamping)
            val adjustment1 = (rankingAdjustmentEntry!!.context["player1RankingAdjustment"] as String).toDouble()
            val adjustment2 = (rankingAdjustmentEntry!!.context["player2RankingAdjustment"] as String).toDouble()
            assertEquals(0.0, adjustment1 + adjustment2, 0.0001)
        }

        @Test
        @DisplayName("Contains rating changes (zero-sum)")
        fun testAuditTrailContainsRatingChanges() {
            val request = createAuditTestRequest()
            val result = calculator.calculate(request)

            val ratingChangesEntry = result.audit.find { it.message.contains("Rating changes") }

            assertTrue(ratingChangesEntry != null, "Audit should contain rating changes entry")
            assertTrue(ratingChangesEntry!!.context.containsKey("player1Change"))
            assertTrue(ratingChangesEntry!!.context.containsKey("player2Change"))

            // Changes should be opposite (zero-sum)
            val change1 = (ratingChangesEntry!!.context["player1Change"] as String).toDouble()
            val change2 = (ratingChangesEntry!!.context["player2Change"] as String).toDouble()
            assertEquals(
                0.0,
                change1 + change2,
                0.0001,
                "Rating changes should be zero-sum before clamping",
            )
        }

        @Test
        @DisplayName("Contains NTRP system-specific changes")
        fun testAuditTrailContainsSystemSpecificChanges() {
            val request = createAuditTestRequest()
            val result = calculator.calculate(request)

            val ntrpChanges = result.audit.filter { it.message.contains("NTRP change") }

            assertEquals(2, ntrpChanges.size, "Should have NTRP change entries for both players")

            ntrpChanges.forEach { entry ->
                assertEquals("NTRP", entry.context["system"])
                assertTrue(entry.context.containsKey("original"))
                assertTrue(entry.context.containsKey("change"))
                assertTrue(entry.context.containsKey("newValue"))
                assertTrue(entry.context.containsKey("clamped"))
            }
        }

        @Test
        @DisplayName("Contains UTR system-specific changes")
        fun testAuditTrailForUTRMatch() {
            val request = createUTRAuditTestRequest()
            val result = calculator.calculate(request)

            val utrChanges = result.audit.filter { it.message.contains("UTR change") }

            assertEquals(2, utrChanges.size, "Should have UTR change entries for both players")

            utrChanges.forEach { entry ->
                assertEquals("UTR", entry.context["system"])
                assertTrue(entry.context.containsKey("original"))
                assertTrue(entry.context.containsKey("change"))
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

            assertTrue(calculatingIndex >= 0, "Should have 'Calculating ranking' entry")
            assertTrue(matchResultIndex >= 0, "Should have 'Match result' entry")
            assertTrue(rankingAdjustmentIndex >= 0, "Should have 'Ranking Adjustment Calculation' entry")
            assertTrue(ratingChangesIndex >= 0, "Should have 'Rating changes' entry")

            // Verify order
            assertTrue(calculatingIndex < matchResultIndex, "Calculation start should come before match result")
            assertTrue(matchResultIndex < rankingAdjustmentIndex, "Match result should come before ranking adjustment")
            assertTrue(
                rankingAdjustmentIndex < ratingChangesIndex,
                "Ranking adjustment should come before rating changes",
            )
        }

        @Test
        @DisplayName("Audit context data is valid")
        fun testAuditContextData() {
            val request = createAuditTestRequest()
            val result = calculator.calculate(request)

            result.audit.forEach { entry ->
                entry.context.values.forEach { value ->
                    assertTrue(
                        value is String || value is Number,
                        "Context value should be String or Number, got ${value::class.simpleName}",
                    )
                }
            }
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
                    assertTrue(entry.context.containsKey("smoothingEnabled"))
                    assertTrue(entry.context.containsKey("smoothingFactor"))
                    assertTrue(entry.context.containsKey("smoothed"))

                    // Should show smoothing is enabled
                    entry.context["smoothingEnabled"] shouldBe "true"
                    entry.context["smoothingFactor"] shouldBe 0.5

                    // Message should mention smoothing
                    assertTrue(entry.message.contains("smoothed"))
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
                assertTrue(p1NewRating <= 7.0)
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
                assertTrue(p1NewRating >= 1.0)
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
            val player1 =
                PlayerProfile(
                    playerId = "P1",
                    name = "Player 1",
                    rating = Rating(value = p1Rating, system = RatingSystem.NTRP),
                )
            val player2 =
                PlayerProfile(
                    playerId = "P2",
                    name = "Player 2",
                    rating = Rating(value = p2Rating, system = RatingSystem.NTRP),
                )

            val winner = if (p1Games > p2Games) "P1" else "P2"

            return RankingCalculationRequest(
                players = mapOf("P1" to player1, "P2" to player2),
                matchScore =
                    MatchScore(
                        sets =
                            listOf(
                                SetScore(
                                    games = mapOf("P1" to p1Games, "P2" to p2Games),
                                    winner = winner,
                                ),
                            ),
                    ),
                options = options,
            )
        }

        private fun createUTRRequest(
            p1Rating: String,
            p2Rating: String,
            p1Games: Int,
            p2Games: Int,
            options: RatingCalculationOptions? = null,
        ): RankingCalculationRequest {
            val player1 =
                PlayerProfile(
                    playerId = "P1",
                    name = "Player 1",
                    rating = Rating(value = p1Rating, system = RatingSystem.UTR),
                )
            val player2 =
                PlayerProfile(
                    playerId = "P2",
                    name = "Player 2",
                    rating = Rating(value = p2Rating, system = RatingSystem.UTR),
                )

            val winner = if (p1Games > p2Games) "P1" else "P2"

            return RankingCalculationRequest(
                players = mapOf("P1" to player1, "P2" to player2),
                matchScore =
                    MatchScore(
                        sets =
                            listOf(
                                SetScore(
                                    games = mapOf("P1" to p1Games, "P2" to p2Games),
                                    winner = winner,
                                ),
                            ),
                    ),
                options = options,
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
                rating = Rating(value = "4.5", system = RatingSystem.NTRP),
            )

        val player2 =
            PlayerProfile(
                playerId = "P456",
                name = "Jane Smith",
                rating = Rating(value = "4.0", system = RatingSystem.NTRP),
            )

        val sets =
            listOf(
                SetScore(
                    games = mapOf("P123" to 6, "P456" to 4),
                    winner = "P123",
                ),
                SetScore(
                    games = mapOf("P123" to 6, "P456" to 3),
                    winner = "P123",
                ),
            )

        return RankingCalculationRequest(
            players = mapOf("P123" to player1, "P456" to player2),
            matchScore = MatchScore(sets = sets),
        )
    }

    private fun createUTRAuditTestRequest(): RankingCalculationRequest {
        val player1 =
            PlayerProfile(
                playerId = "P789",
                name = "Mike Wilson",
                rating = Rating(value = "8.5", system = RatingSystem.UTR),
            )

        val player2 =
            PlayerProfile(
                playerId = "P101",
                name = "Sarah Lee",
                rating = Rating(value = "8.2", system = RatingSystem.UTR),
            )

        val sets =
            listOf(
                SetScore(
                    games = mapOf("P789" to 6, "P101" to 4),
                    winner = "P789",
                ),
            )

        return RankingCalculationRequest(
            players = mapOf("P789" to player1, "P101" to player2),
            matchScore = MatchScore(sets = sets),
        )
    }
}
