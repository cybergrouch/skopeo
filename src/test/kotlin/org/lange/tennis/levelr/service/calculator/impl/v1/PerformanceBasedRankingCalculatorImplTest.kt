package org.lange.tennis.levelr.service.calculator.impl.v1

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
         * Test scenarios with expected deltas.
         *
         * Format: scenario, p1Rating, p2Rating, p1Games, p2Games, winner, expectedP1Delta, expectedP2Delta, description
         *
         * Easy to update when tuning constants - just change the expected values here.
         */
        @JvmStatic
        fun testScenarios(): Stream<Arguments> =
            Stream.of(
                // Expected wins - higher-rated beats lower-rated
                Arguments.of("S1", "4.5", "4.0", 6, 0, "P1", "0.080000", "-0.080000", "Higher-rated wins 6-0 (diff=0.5)"),
                Arguments.of("S2", "4.5", "4.0", 6, 4, "P1", "0.016000", "-0.016000", "Higher-rated wins 6-4 (diff=0.5)"),
                Arguments.of("S11", "4.5", "4.0", 7, 5, "P1", "0.013333", "-0.013333", "Higher-rated wins 7-5 (very close)"),
                // Competitive matches - small rating gaps
                Arguments.of("S3", "4.5", "4.3", 6, 0, "P1", "0.128000", "-0.128000", "Higher-rated wins 6-0 (diff=0.2, competitive)"),
                Arguments.of("S4", "4.5", "4.3", 6, 4, "P1", "0.025600", "-0.025600", "Higher-rated wins 6-4 (diff=0.2, competitive)"),
                // Upsets - lower-rated beats higher-rated
                Arguments.of("S5", "4.0", "4.5", 6, 0, "P1", "0.160000", "-0.160000", "Lower-rated wins 6-0 (UPSET, diff=0.5)"),
                Arguments.of("S6", "4.0", "4.5", 6, 4, "P1", "0.032000", "-0.032000", "Lower-rated wins 6-4 (UPSET, diff=0.5)"),
                Arguments.of("S12", "4.0", "4.5", 7, 5, "P1", "0.026667", "-0.026667", "Lower-rated wins 7-5 (close upset)"),
                // Large gaps - threshold at 1.0
                Arguments.of("S7", "5.0", "4.0", 6, 0, "P1", "0.000000", "0.000000", "Much higher-rated wins 6-0 (diff=1.0)"),
                Arguments.of("S8", "4.0", "5.0", 6, 0, "P1", "0.320000", "-0.320000", "Much lower-rated wins 6-0 (BIG UPSET, diff=1.0)"),
                // Equal players - should get performance-based deltas
                Arguments.of("S9", "4.5", "4.5", 6, 0, "P1", "0.160000", "-0.160000", "Equal players, 6-0 win"),
                Arguments.of("S10", "4.5", "4.5", 6, 4, "P1", "0.032000", "-0.032000", "Equal players, 6-4 win"),
                // Very large gaps
                Arguments.of("S13", "6.0", "4.0", 6, 0, "P1", "0.000000", "0.000000", "Very large gap, higher wins (diff=2.0)"),
                Arguments.of("S14", "4.0", "6.0", 6, 0, "P1", "0.640000", "-0.640000", "Very large gap upset (diff=2.0)"),
                // Different rating levels - verify consistency
                Arguments.of("S15", "3.5", "3.0", 6, 0, "P1", "0.080000", "-0.080000", "Lower ratings, dominant win (diff=0.5)"),
                Arguments.of("S16", "5.5", "5.0", 6, 4, "P1", "0.016000", "-0.016000", "Higher ratings, close win (diff=0.5)"),
            )
    }

    private fun createRequest(
        p1Rating: String,
        p2Rating: String,
        p1Games: Int,
        p2Games: Int,
        winner: String,
    ): RankingCalculationRequest {
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
        )
    }

    @ParameterizedTest(name = "{0}: {8}")
    @MethodSource("testScenarios")
    @DisplayName("Rating delta calculations")
    fun testRatingDeltas(
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
        val result = calculator.calculate(createRequest(p1Rating, p2Rating, p1Games, p2Games, winner))

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
        val result60 = calculator.calculate(createRequest("4.5", "4.0", 6, 0, "P1"))
        val result64 = calculator.calculate(createRequest("4.5", "4.0", 6, 4, "P1"))

        val delta60 = result60.response.ratingChanges["P1"]?.change?.toDouble() ?: 0.0
        val delta64 = result64.response.ratingChanges["P1"]?.change?.toDouble() ?: 0.0

        val ratio = delta60 / delta64
        assertEquals(5.0, ratio, 0.01, "6-0 should give 5× more change than 6-4")
    }

    @Test
    @DisplayName("Upset multiplier: upsets should give 2× more change than expected wins")
    fun testUpsetMultiplier() {
        val expected = calculator.calculate(createRequest("4.5", "4.0", 6, 0, "P1"))
        val upset = calculator.calculate(createRequest("4.0", "4.5", 6, 0, "P1"))

        val expectedDelta = expected.response.ratingChanges["P1"]?.change?.toDouble() ?: 0.0
        val upsetDelta = upset.response.ratingChanges["P1"]?.change?.toDouble() ?: 0.0

        val ratio = upsetDelta / expectedDelta
        assertEquals(2.0, ratio, 0.01, "Upset should give 2× more change than expected win")
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
