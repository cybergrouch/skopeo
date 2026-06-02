package org.lange.tennis.levelr.service.calculator.impl.v1

import org.lange.tennis.levelr.dto.RankingCalculationRequest
import org.lange.tennis.levelr.model.MatchScore
import org.lange.tennis.levelr.model.PlayerProfile
import org.lange.tennis.levelr.model.Rating
import org.lange.tennis.levelr.model.RatingSystem
import org.lange.tennis.levelr.model.SetScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the audit trail functionality of RankingCalculator.
 * These tests verify that the calculator produces the expected audit entries
 * without requiring actual logging infrastructure.
 */
class PerformanceBasedRankingCalculatorImplAuditTest {
    private val calculator = PerformanceBasedRankingCalculatorImpl()

    @Test
    fun testAuditTrailContainsCalculationStart() {
        val request = createSimpleRequest()

        val result = calculator.calculate(request)

        // Verify audit trail contains calculation start message
        val startEntry =
            result.audit.find {
                it.message.contains("Calculating ranking")
            }

        assertTrue(actual = startEntry != null, message = "Audit should contain calculation start entry")
        assertEquals(expected = "John Doe", actual = startEntry.context["player1"])
        assertEquals(expected = "4.5", actual = startEntry.context["player1Rating"])
        assertEquals(expected = "Jane Smith", actual = startEntry.context["player2"])
        assertEquals(expected = "4.0", actual = startEntry.context["player2Rating"])
    }

    @Test
    fun testAuditTrailContainsMatchResult() {
        val request = createSimpleRequest()

        val result = calculator.calculate(request)

        // Verify audit trail contains match result analysis
        val matchResultEntry =
            result.audit.find {
                it.message.contains("Match result")
            }

        assertTrue(actual = matchResultEntry != null, message = "Audit should contain match result entry")
        assertTrue(matchResultEntry.context.containsKey("winnerId"))
        assertTrue(matchResultEntry.context.containsKey("dominanceFactor"))
    }

    @Test
    fun testAuditTrailContainsExpectedScores() {
        val request = createSimpleRequest()

        val result = calculator.calculate(request)

        // Verify audit trail contains expected scores
        val expectedScoresEntry =
            result.audit.find {
                it.message.contains("Expected scores")
            }

        assertTrue(actual = expectedScoresEntry != null, message = "Audit should contain expected scores entry")
        assertTrue(expectedScoresEntry.context.containsKey("expectedPlayer1"))
        assertTrue(expectedScoresEntry.context.containsKey("expectedPlayer2"))

        // Expected scores should sum to 1.0
        val expected1 = (expectedScoresEntry.context["expectedPlayer1"] as String).toDouble()
        val expected2 = (expectedScoresEntry.context["expectedPlayer2"] as String).toDouble()
        assertEquals(expected = 1.0, actual = expected1 + expected2, absoluteTolerance = 0.0001)
    }

    @Test
    fun testAuditTrailContainsRatingChanges() {
        val request = createSimpleRequest()

        val result = calculator.calculate(request)

        // Verify audit trail contains rating changes
        val ratingChangesEntry =
            result.audit.find {
                it.message.contains("Rating changes")
            }

        assertTrue(actual = ratingChangesEntry != null, message = "Audit should contain rating changes entry")
        assertTrue(ratingChangesEntry.context.containsKey("player1Change"))
        assertTrue(ratingChangesEntry.context.containsKey("player2Change"))

        // Changes should be opposite (zero-sum)
        val change1 = (ratingChangesEntry.context["player1Change"] as String).toDouble()
        val change2 = (ratingChangesEntry.context["player2Change"] as String).toDouble()
        assertEquals(
            expected = 0.0,
            actual = change1 + change2,
            absoluteTolerance = 0.0001,
            message = "Rating changes should be zero-sum before clamping",
        )
    }

    @Test
    fun testAuditTrailContainsSystemSpecificChanges() {
        val request = createSimpleRequest()

        val result = calculator.calculate(request)

        // Verify audit trail contains NTRP-specific changes
        val ntrpChanges =
            result.audit.filter {
                it.message.contains("NTRP change")
            }

        assertEquals(expected = 2, actual = ntrpChanges.size, message = "Should have NTRP change entries for both players")

        // Verify each entry has detailed context
        ntrpChanges.forEach { entry ->
            assertEquals(expected = "NTRP", actual = entry.context["system"])
            assertTrue(entry.context.containsKey("original"))
            assertTrue(entry.context.containsKey("change"))
            assertTrue(entry.context.containsKey("newValue"))
            assertTrue(entry.context.containsKey("clamped"))
        }
    }

    @Test
    fun testAuditTrailForUTRMatch() {
        val request = createUTRRequest()

        val result = calculator.calculate(request)

        // Verify audit trail contains UTR-specific changes
        val utrChanges =
            result.audit.filter {
                it.message.contains("UTR change")
            }

        assertEquals(expected = 2, actual = utrChanges.size, message = "Should have UTR change entries for both players")

        // Verify each entry has detailed context
        utrChanges.forEach { entry ->
            assertEquals(expected = "UTR", actual = entry.context["system"])
            assertTrue(entry.context.containsKey("original"))
            assertTrue(entry.context.containsKey("change"))
        }
    }

    @Test
    fun testAuditTrailOrder() {
        val request = createSimpleRequest()

        val result = calculator.calculate(request)

        // Verify audit entries are in expected order
        val messages = result.audit.map { it.message }

        val calculatingIndex = messages.indexOfFirst { it.contains("Calculating ranking") }
        val matchResultIndex = messages.indexOfFirst { it.contains("Match result") }
        val expectedScoresIndex = messages.indexOfFirst { it.contains("Expected scores") }
        val ratingChangesIndex = messages.indexOfFirst { it.contains("Rating changes") }

        assertTrue(actual = calculatingIndex >= 0, message = "Should have 'Calculating ranking' entry")
        assertTrue(actual = matchResultIndex >= 0, message = "Should have 'Match result' entry")
        assertTrue(actual = expectedScoresIndex >= 0, message = "Should have 'Expected scores' entry")
        assertTrue(actual = ratingChangesIndex >= 0, message = "Should have 'Rating changes' entry")

        // Verify order
        assertTrue(actual = calculatingIndex < matchResultIndex, message = "Calculation start should come before match result")
        assertTrue(actual = matchResultIndex < expectedScoresIndex, message = "Match result should come before expected scores")
        assertTrue(actual = expectedScoresIndex < ratingChangesIndex, message = "Expected scores should come before rating changes")
    }

    @Test
    fun testAuditContextData() {
        val request = createSimpleRequest()

        val result = calculator.calculate(request)

        // Verify all audit entries with context have valid data
        result.audit.forEach { entry ->
            entry.context.values.forEach { value ->
                // All context values should be non-null and of expected types
                assertTrue(
                    actual = value is String || value is Number,
                    message = "Context value should be String or Number, got ${value::class.simpleName}",
                )
            }
        }
    }

    // Helper functions to create test requests

    private fun createSimpleRequest(): RankingCalculationRequest {
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

    private fun createUTRRequest(): RankingCalculationRequest {
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
