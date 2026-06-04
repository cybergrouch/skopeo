package org.lange.tennis.levelr.service.calculator.impl.v1

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
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
 * Uses parameterized tests for easy verification of expected deltas.
 * All test data is defined in testScenarios() method for easy review and tuning.
 *
 * Tests cover:
 * - Expected wins (higher-rated beats lower-rated)
 * - Upsets (lower-rated beats higher-rated)
 * - Competitive matches (small rating gaps)
 * - Equal players (same rating)
 * - Various score margins (6-0, 6-4, 7-5)
 * - Large rating gaps
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
}
