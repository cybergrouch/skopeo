package org.lange.tennis.levelr.service.calculator

import org.lange.tennis.levelr.dto.RankingCalculationRequest
import org.lange.tennis.levelr.model.MatchScore
import org.lange.tennis.levelr.model.PlayerProfile
import org.lange.tennis.levelr.model.Rating
import org.lange.tennis.levelr.model.RatingSystem
import org.lange.tennis.levelr.model.SetScore
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure unit tests for RankingCalculator.
 * These tests don't require any infrastructure - they test the calculator in complete isolation.
 */
class PerformanceBasedRankingCalculatorImplUnitTest {
    private val calculator = PerformanceBasedRankingCalculatorImpl()

    // ========== NTRP Tests ==========

    @Test
    fun testNTRP_EqualPlayers_WinnerGainsRating() {
        val request =
            createRequest(
                player1Rating = 4.5,
                player2Rating = 4.5,
                system = RatingSystem.NTRP,
                sets = listOf(SetScore(games = mapOf("P1" to 6, "P2" to 4), winner = "P1")),
            )

        val result = calculator.calculate(request)

        // Winner should gain rating
        val p1Change = result.response.ratingChanges["P1"]!!.change.toDouble()
        assertTrue(actual = p1Change > 0, message = "Winner should gain rating, got $p1Change")

        // Loser should lose rating
        val p2Change = result.response.ratingChanges["P2"]!!.change.toDouble()
        assertTrue(actual = p2Change < 0, message = "Loser should lose rating, got $p2Change")
    }

    @Test
    fun testNTRP_RespectsBoundaries_Max() {
        val request =
            createRequest(
                // Already at max
                player1Rating = 7.0,
                player2Rating = 6.5,
                system = RatingSystem.NTRP,
                sets = listOf(SetScore(games = mapOf("P1" to 6, "P2" to 0), winner = "P1")),
            )

        val result = calculator.calculate(request)

        // Should not exceed 7.0
        val newRating = result.response.players["P1"]!!.rating.value.toDouble()
        assertTrue(actual = newRating <= 7.0, message = "NTRP should not exceed 7.0, got $newRating")
    }

    @Test
    fun testNTRP_RespectsBoundaries_Min() {
        val request =
            createRequest(
                // Already at min
                player1Rating = 1.0,
                player2Rating = 5.0,
                system = RatingSystem.NTRP,
                sets = listOf(SetScore(games = mapOf("P1" to 0, "P2" to 6), winner = "P2")),
            )

        val result = calculator.calculate(request)

        // Should not go below 1.0
        val newRating = result.response.players["P1"]!!.rating.value.toDouble()
        assertTrue(actual = newRating >= 1.0, message = "NTRP should not go below 1.0, got $newRating")
    }

    @Test
    fun testNTRP_MaintainsPrecision() {
        val request =
            createRequest(
                player1Rating = 4.5,
                player2Rating = 4.0,
                system = RatingSystem.NTRP,
                sets = listOf(SetScore(games = mapOf("P1" to 6, "P2" to 4), winner = "P1")),
            )

        val result = calculator.calculate(request)

        // Check both players have at most 6 decimal places (CALCULATION_SCALE)
        val p1Rating = result.response.players["P1"]!!.rating.value
        val p2Rating = result.response.players["P2"]!!.rating.value

        assertTrue(
            actual = hasAtMostNDecimals(value = p1Rating, maxDecimals = 6),
            message = "P1 rating should have at most 6 decimals, got: $p1Rating",
        )
        assertTrue(
            actual = hasAtMostNDecimals(value = p2Rating, maxDecimals = 6),
            message = "P2 rating should have at most 6 decimals, got: $p2Rating",
        )
    }

    // ========== UTR Tests ==========

    @Test
    fun testUTR_EqualPlayers_WinnerGainsRating() {
        val request =
            createRequest(
                player1Rating = 10.0,
                player2Rating = 10.0,
                system = RatingSystem.UTR,
                sets = listOf(SetScore(games = mapOf("P1" to 6, "P2" to 3), winner = "P1")),
            )

        val result = calculator.calculate(request)

        // Winner should gain rating
        val p1Change = result.response.ratingChanges["P1"]!!.change.toDouble()
        assertTrue(actual = p1Change > 0, message = "Winner should gain rating")

        // Loser should lose rating
        val p2Change = result.response.ratingChanges["P2"]!!.change.toDouble()
        assertTrue(actual = p2Change < 0, message = "Loser should lose rating")
    }

    @Test
    fun testUTR_RespectsMinimum() {
        val request =
            createRequest(
                // At minimum
                player1Rating = 1.0,
                player2Rating = 8.0,
                system = RatingSystem.UTR,
                sets = listOf(SetScore(games = mapOf("P1" to 0, "P2" to 6), winner = "P2")),
            )

        val result = calculator.calculate(request)

        // Should not go below 1.0
        val newRating = result.response.players["P1"]!!.rating.value.toDouble()
        assertTrue(newRating >= 1.0, "UTR should not go below 1.0, got $newRating")
    }

    @Test
    fun testUTR_NoMaximum() {
        val request =
            createRequest(
                // Very high rating
                player1Rating = 16.0,
                player2Rating = 15.5,
                system = RatingSystem.UTR,
                sets = listOf(SetScore(games = mapOf("P1" to 6, "P2" to 4), winner = "P1")),
            )

        val result = calculator.calculate(request)

        // With new algorithm: 6-4 score is below baseline (expected ~6-3)
        // So changes are inverted: favorite (P1) loses rating
        // But UTR has no maximum, so we verify that rating changes can occur
        // and are not capped at an upper bound
        val newRating = result.response.players["P1"]!!.rating.value.toDouble()
        val change = result.response.ratingChanges["P1"]!!.change.toDouble()

        // UTR has no maximum - rating can be any value >= 1.0
        assertTrue(newRating >= 1.0, "UTR minimum is 1.0")
        // Change should be non-zero (inverted in this case)
        assertTrue(change != 0.0, "Rating should change (no artificial maximum preventing changes)")
    }

    @Test
    fun testUTR_MaintainsPrecision() {
        val request =
            createRequest(
                player1Rating = 8.5,
                player2Rating = 8.2,
                system = RatingSystem.UTR,
                sets = listOf(SetScore(games = mapOf("P1" to 6, "P2" to 4), winner = "P1")),
            )

        val result = calculator.calculate(request)

        // Check both players have at most 6 decimal places (CALCULATION_SCALE)
        val p1Rating = result.response.players["P1"]!!.rating.value
        val p2Rating = result.response.players["P2"]!!.rating.value

        assertTrue(
            actual = hasAtMostNDecimals(value = p1Rating, maxDecimals = 6),
            message = "P1 rating should have at most 6 decimals, got: $p1Rating",
        )
        assertTrue(
            actual = hasAtMostNDecimals(value = p2Rating, maxDecimals = 6),
            message = "P2 rating should have at most 6 decimals, got: $p2Rating",
        )
    }

    // ========== Dominance Factor Tests ==========

    @Test
    fun testDominanceFactor_DominantWin() {
        val dominantWin =
            createRequest(
                player1Rating = 5.0,
                player2Rating = 5.0,
                system = RatingSystem.NTRP,
                // 6-0 = dominant
                sets = listOf(SetScore(games = mapOf("P1" to 6, "P2" to 0), winner = "P1")),
            )

        val closeWin =
            createRequest(
                player1Rating = 5.0,
                player2Rating = 5.0,
                system = RatingSystem.NTRP,
                // 7-5 = close
                sets = listOf(SetScore(games = mapOf("P1" to 7, "P2" to 5), winner = "P1")),
            )

        val dominantResult = calculator.calculate(dominantWin)
        val closeResult = calculator.calculate(closeWin)

        val dominantChange = abs(dominantResult.response.ratingChanges["P1"]!!.change.toDouble())
        val closeChange = abs(closeResult.response.ratingChanges["P1"]!!.change.toDouble())

        // Dominant win should produce larger rating change (unless both get clamped)
        // This test may not always pass due to clamping, but shows the concept
        assertTrue(actual = dominantChange >= closeChange, message = "Dominant win should produce >= rating change")
    }

    @Test
    fun testDominanceFactor_ThreeSetMatch() {
        val request =
            createRequest(
                player1Rating = 5.0,
                player2Rating = 5.0,
                system = RatingSystem.NTRP,
                sets =
                    listOf(
                        SetScore(games = mapOf("P1" to 6, "P2" to 4), winner = "P1"),
                        SetScore(games = mapOf("P1" to 3, "P2" to 6), winner = "P2"),
                        SetScore(games = mapOf("P1" to 6, "P2" to 2), winner = "P1"),
                    ),
            )

        val result = calculator.calculate(request)

        // Match winner (P1) should gain rating
        val p1Change = result.response.ratingChanges["P1"]!!.change.toDouble()
        assertTrue(actual = p1Change > 0, message = "Match winner should gain rating")
    }

    // ========== Expected Score Tests ==========

    @Test
    fun testExpectedScore_HigherRatedFavored() {
        val request =
            createRequest(
                // Higher rated
                player1Rating = 6.0,
                player2Rating = 4.0,
                system = RatingSystem.NTRP,
                sets = listOf(SetScore(games = mapOf("P1" to 6, "P2" to 4), winner = "P1")),
            )

        val result = calculator.calculate(request)

        // Check audit trail for expected scores
        val expectedScoreEntry =
            result.audit.find {
                it.message.contains("Expected scores")
            }!!

        val expected1 = (expectedScoreEntry.context["expectedPlayer1"] as String).toDouble()
        val expected2 = (expectedScoreEntry.context["expectedPlayer2"] as String).toDouble()

        // Higher rated player should have higher expected score
        assertTrue(actual = expected1 > expected2, message = "Higher rated player should be favored")
        assertEquals(
            expected = 1.0,
            actual = expected1 + expected2,
            absoluteTolerance = 0.0001,
            message = "Expected scores should sum to 1",
        )
    }

    @Test
    fun testExpectedScore_EqualRatings() {
        val request =
            createRequest(
                player1Rating = 5.0,
                player2Rating = 5.0,
                system = RatingSystem.NTRP,
                sets = listOf(SetScore(games = mapOf("P1" to 6, "P2" to 4), winner = "P1")),
            )

        val result = calculator.calculate(request)

        // Check audit trail for expected scores
        val expectedScoreEntry =
            result.audit.find {
                it.message.contains("Expected scores")
            }!!

        val expected1 = (expectedScoreEntry.context["expectedPlayer1"] as String).toDouble()
        val expected2 = (expectedScoreEntry.context["expectedPlayer2"] as String).toDouble()

        // Equal ratings = 50/50 chance
        assertEquals(
            expected = 0.5,
            actual = expected1,
            absoluteTolerance = 0.01,
            message = "Equal ratings should give ~0.5 expected score",
        )
        assertEquals(
            expected = 0.5,
            actual = expected2,
            absoluteTolerance = 0.01,
            message = "Equal ratings should give ~0.5 expected score",
        )
    }

    // ========== Upset Tests ==========

    @Test
    fun testUpset_UnderdogWins() {
        val request =
            createRequest(
                // Underdog
                player1Rating = 3.0,
                // Favorite
                player2Rating = 6.0,
                system = RatingSystem.NTRP,
                sets = listOf(SetScore(games = mapOf("P1" to 6, "P2" to 4), winner = "P1")),
            )

        val result = calculator.calculate(request)

        // Underdog should gain rating
        val underdogChange = result.response.ratingChanges["P1"]!!.change.toDouble()
        assertTrue(actual = underdogChange > 0, message = "Underdog should gain rating for upset win")

        // Favorite should lose rating
        val favoriteChange = result.response.ratingChanges["P2"]!!.change.toDouble()
        assertTrue(actual = favoriteChange < 0, message = "Favorite should lose rating for upset loss")
    }

    @Test
    fun testExpectedResult_FavoriteWins() {
        val request =
            createRequest(
                // Favorite
                player1Rating = 6.0,
                // Underdog
                player2Rating = 3.0,
                system = RatingSystem.NTRP,
                sets = listOf(SetScore(games = mapOf("P1" to 6, "P2" to 2), winner = "P1")),
            )

        val result = calculator.calculate(request)

        // Both players should have rating changes
        val favoriteChange = result.response.ratingChanges["P1"]!!.change.toDouble()
        val underdogChange = result.response.ratingChanges["P2"]!!.change.toDouble()

        // With new algorithm: 6-2 score with 3.0 rating difference
        // Expected margin = 3.0 × 6 = 18 games
        // Actual margin = 4 games (6-2)
        // Performance ratio = 4/18 < 1.0, so changes are INVERTED
        // Favorite LOSES rating for underperforming
        assertTrue(actual = favoriteChange < 0, message = "Favorite should lose rating (underperformed)")
        // Underdog GAINS rating despite losing
        assertTrue(actual = underdogChange > 0, message = "Underdog should gain rating (performed above expectation)")
    }

    // ========== Pure Function Tests ==========

    @Test
    fun testPureFunction_Deterministic() {
        val request =
            createRequest(
                player1Rating = 4.5,
                player2Rating = 4.0,
                system = RatingSystem.NTRP,
                sets = listOf(SetScore(games = mapOf("P1" to 6, "P2" to 4), winner = "P1")),
            )

        // Call multiple times with same input
        val result1 = calculator.calculate(request)
        val result2 = calculator.calculate(request)
        val result3 = calculator.calculate(request)

        // Should produce identical results
        assertEquals<String>(
            result1.response.players["P1"]!!.rating.value,
            result2.response.players["P1"]!!.rating.value,
        )
        assertEquals<String>(
            result1.response.players["P1"]!!.rating.value,
            result3.response.players["P1"]!!.rating.value,
        )
        assertEquals<String>(
            result1.response.players["P2"]!!.rating.value,
            result2.response.players["P2"]!!.rating.value,
        )
    }

    @Test
    fun testPureFunction_NoSideEffects() {
        val originalRequest =
            createRequest(
                player1Rating = 4.5,
                player2Rating = 4.0,
                system = RatingSystem.NTRP,
                sets = listOf(SetScore(games = mapOf("P1" to 6, "P2" to 4), winner = "P1")),
            )

        // Store original values
        val originalP1Rating = originalRequest.players["P1"]!!.rating.value
        val originalP2Rating = originalRequest.players["P2"]!!.rating.value

        // Calculate
        calculator.calculate(originalRequest)

        // Original request should be unchanged (no mutation)
        assertEquals<String>(originalP1Rating, originalRequest.players["P1"]!!.rating.value)
        assertEquals<String>(originalP2Rating, originalRequest.players["P2"]!!.rating.value)
    }

    // ========== Audit Trail Integration Tests ==========

    @Test
    fun testAuditTrail_ContainsAllExpectedEntries() {
        val request =
            createRequest(
                player1Rating = 4.5,
                player2Rating = 4.0,
                system = RatingSystem.NTRP,
                sets = listOf(SetScore(games = mapOf("P1" to 6, "P2" to 4), winner = "P1")),
            )

        val result = calculator.calculate(request)

        // Should have key audit entries
        assertTrue(actual = result.audit.any { it.message.contains("Calculating ranking") })
        assertTrue(actual = result.audit.any { it.message.contains("Match result") })
        assertTrue(actual = result.audit.any { it.message.contains("Expected scores") })
        assertTrue(actual = result.audit.any { it.message.contains("Rating changes") })
        assertTrue(actual = result.audit.any { it.message.contains("NTRP change") })
    }

    @Test
    fun testAuditTrail_ContainsStructuredContext() {
        val request =
            createRequest(
                player1Rating = 4.5,
                player2Rating = 4.0,
                system = RatingSystem.NTRP,
                sets = listOf(SetScore(games = mapOf("P1" to 6, "P2" to 4), winner = "P1")),
            )

        val result = calculator.calculate(request)

        // Find the calculation start entry
        val startEntry =
            result.audit.find {
                it.message.contains("Calculating ranking")
            }!!

        // Should have structured context
        assertEquals<Any?>("4.5", startEntry.context["player1Rating"])
        assertEquals<Any?>("4.0", startEntry.context["player2Rating"])
    }

    // ========== Response Structure Tests ==========

    @Test
    fun testResponse_ContainsUpdatedPlayers() {
        val request =
            createRequest(
                player1Rating = 4.5,
                player2Rating = 4.0,
                system = RatingSystem.NTRP,
                sets = listOf(SetScore(games = mapOf("P1" to 6, "P2" to 4), winner = "P1")),
            )

        val result = calculator.calculate(request)

        // Should have both players
        assertTrue(actual = result.response.players.containsKey("P1"))
        assertTrue(actual = result.response.players.containsKey("P2"))

        // Players should have updated ratings
        val p1 = result.response.players["P1"]!!
        val p2 = result.response.players["P2"]!!

        assertEquals<String>("P1", p1.playerId)
        assertEquals<String>("P2", p2.playerId)
        assertEquals<RatingSystem>(RatingSystem.NTRP, p1.rating.system)
        assertEquals<RatingSystem>(RatingSystem.NTRP, p2.rating.system)
    }

    @Test
    fun testResponse_ContainsRatingChanges() {
        val request =
            createRequest(
                player1Rating = 4.5,
                player2Rating = 4.0,
                system = RatingSystem.NTRP,
                sets = listOf(SetScore(games = mapOf("P1" to 6, "P2" to 4), winner = "P1")),
            )

        val result = calculator.calculate(request)

        // Should have rating changes for both players
        assertTrue(actual = result.response.ratingChanges.containsKey("P1"))
        assertTrue(actual = result.response.ratingChanges.containsKey("P2"))

        val p1Change = result.response.ratingChanges["P1"]!!
        val p2Change = result.response.ratingChanges["P2"]!!

        // Should have all fields
        assertEquals<String>("4.5", p1Change.previousRating.value)
        assertEquals<String>("4.0", p2Change.previousRating.value)

        // Change should match difference
        assertEquals(
            expected = p1Change.newRating.value.toDouble() - p1Change.previousRating.value.toDouble(),
            actual = p1Change.change.toDouble(),
            absoluteTolerance = 0.0001,
        )
    }

    @Test
    fun testResponse_PercentChangeCalculation() {
        val request =
            createRequest(
                player1Rating = 4.0,
                player2Rating = 4.0,
                system = RatingSystem.NTRP,
                sets = listOf(SetScore(games = mapOf("P1" to 6, "P2" to 4), winner = "P1")),
            )

        val result = calculator.calculate(request)

        val p1Change = result.response.ratingChanges["P1"]!!

        // Percent change should be calculated correctly
        val expectedPercent = (p1Change.change.toDouble() / 4.0) * 100
        assertEquals(expected = expectedPercent, actual = p1Change.percentChange.toDouble(), absoluteTolerance = 0.01)
    }

    // ========== Helper Methods ==========

    private fun createRequest(
        player1Rating: Double,
        player2Rating: Double,
        system: RatingSystem,
        sets: List<SetScore>,
    ): RankingCalculationRequest {
        val player1 =
            PlayerProfile(
                playerId = "P1",
                name = "Player 1",
                rating = Rating(value = player1Rating.toString(), system = system),
            )

        val player2 =
            PlayerProfile(
                playerId = "P2",
                name = "Player 2",
                rating = Rating(value = player2Rating.toString(), system = system),
            )

        return RankingCalculationRequest(
            players = mapOf("P1" to player1, "P2" to player2),
            matchScore = MatchScore(sets = sets),
        )
    }

    private fun roundToDecimals(
        value: Double,
        decimals: Int,
    ): Double {
        val multiplier = Math.pow(10.0, decimals.toDouble())
        return Math.round(value * multiplier) / multiplier
    }

    private fun hasAtMostNDecimals(
        value: String,
        maxDecimals: Int,
    ): Boolean {
        val parts = value.split(".")
        return if (parts.size == 1) {
            true // No decimal point, so 0 decimals
        } else {
            parts[1].length <= maxDecimals
        }
    }
}
