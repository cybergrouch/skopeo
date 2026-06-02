package org.lange.tennis.levelr.service.calculator.impl.v1

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.lange.tennis.levelr.dto.RankingCalculationRequest
import org.lange.tennis.levelr.model.MatchScore
import org.lange.tennis.levelr.model.PlayerProfile
import org.lange.tennis.levelr.model.Rating
import org.lange.tennis.levelr.model.RatingSystem
import org.lange.tennis.levelr.model.SetScore
import org.lange.tennis.levelr.model.TiebreakScore

/**
 * Edge case tests for PerformanceBasedRankingCalculator.
 *
 * These tests capture current algorithm behavior for edge cases to prevent regressions.
 * They are NOT intended to validate correctness, but to document and preserve existing behavior.
 */
class PerformanceBasedRankingCalculatorImplEdgeCasesTest {
    private val calculator = PerformanceBasedRankingCalculatorImpl()

    @Test
    fun `testExtremeRatingGap_FivePointDifference - large gaps result in zero change`() {
        // Edge case: When rating gap is 5.0, expected margin (30 games) exceeds
        // maximum possible games (12 for 6-0, 6-0), so expected = actual → zero change
        val request =
            RankingCalculationRequest(
                players =
                    mapOf(
                        "BEGINNER" to
                            PlayerProfile(
                                playerId = "BEGINNER",
                                name = "Beginner",
                                rating = Rating("1.0", RatingSystem.NTRP),
                            ),
                        "EXPERT" to
                            PlayerProfile(
                                playerId = "EXPERT",
                                name = "Expert",
                                rating = Rating("6.0", RatingSystem.NTRP),
                            ),
                    ),
                matchScore =
                    MatchScore(
                        sets =
                            listOf(
                                SetScore(games = mapOf("BEGINNER" to 0, "EXPERT" to 6), winner = "EXPERT"),
                                SetScore(games = mapOf("BEGINNER" to 0, "EXPERT" to 6), winner = "EXPERT"),
                            ),
                    ),
            )

        val result = calculator.calculate(request)

        // Expected behavior: Zero change due to margin capping
        // uncappedExpectedMargin = 5.0 × 6 = 30 games
        // maxPossibleMargin = 12 games (expert won 12-0)
        // expectedMargin = 12 (capped)
        // actualMargin = 12
        // Difference < 0.1 → "Met Expectations" case → zero change
        result.response.players["EXPERT"]!!.rating.value shouldBe "6.000000"
        result.response.players["BEGINNER"]!!.rating.value shouldBe "1.000000"

        // Verify rating changes are exactly zero
        result.response.ratingChanges["EXPERT"]!!.change shouldBe "0.000000"
        result.response.ratingChanges["BEGINNER"]!!.change shouldBe "0.000000"
    }

    @Test
    fun `testDominanceNearCap_TwoPointFourRatio - just below 2-5 cap`() {
        // Edge case: Dominance = 12/5 = 2.4, which is below the 2.5 cap
        // This should NOT be capped
        val request =
            RankingCalculationRequest(
                players =
                    mapOf(
                        "P1" to PlayerProfile(playerId = "P1", name = "Player 1", rating = Rating("5.0", RatingSystem.NTRP)),
                        "P2" to PlayerProfile(playerId = "P2", name = "Player 2", rating = Rating("4.5", RatingSystem.NTRP)),
                    ),
                matchScore =
                    MatchScore(
                        sets =
                            listOf(
                                SetScore(games = mapOf("P1" to 6, "P2" to 2), winner = "P1"),
                                SetScore(games = mapOf("P1" to 6, "P2" to 3), winner = "P1"),
                            ),
                    ),
            )

        val result = calculator.calculate(request)

        // Verify P1 gains rating (higher rated player won decisively)
        val p1Change = result.response.ratingChanges["P1"]!!.change.toBigDecimal()
        val p2Change = result.response.ratingChanges["P2"]!!.change.toBigDecimal()

        // Changes should be non-zero since dominance is 2.4 (12 games vs 5 games)
        p1Change shouldNotBe "0.000000".toBigDecimal()
        p2Change shouldNotBe "0.000000".toBigDecimal()

        // Should be zero-sum before clamping
        (p1Change + p2Change).abs() shouldBeLessThan "0.0001".toBigDecimal()
    }

    @Test
    fun `testDominanceAboveCap_ThreePointZero - dominance exceeds cap`() {
        // Edge case: Dominance = 18/6 = 3.0, exceeds 2.5 cap → should be capped
        val request =
            RankingCalculationRequest(
                players =
                    mapOf(
                        "P1" to PlayerProfile(playerId = "P1", name = "Player 1", rating = Rating("5.0", RatingSystem.NTRP)),
                        "P2" to PlayerProfile(playerId = "P2", name = "Player 2", rating = Rating("4.5", RatingSystem.NTRP)),
                    ),
                matchScore =
                    MatchScore(
                        sets =
                            listOf(
                                SetScore(games = mapOf("P1" to 6, "P2" to 2), winner = "P1"),
                                SetScore(games = mapOf("P1" to 6, "P2" to 1), winner = "P1"),
                                SetScore(games = mapOf("P1" to 6, "P2" to 3), winner = "P1"),
                            ),
                    ),
            )

        val result = calculator.calculate(request)

        // Verify changes are calculated (dominance = 18/6 = 3.0, capped to 2.5)
        val p1Change = result.response.ratingChanges["P1"]!!.change.toBigDecimal()
        p1Change shouldNotBe "0.000000".toBigDecimal()
    }

    @Test
    fun `testNearEqualRatings_BelowThreshold - 0-009 difference treated as equal`() {
        // Edge case: Rating diff = 0.009, below 0.01 threshold → treated as equal
        val request =
            RankingCalculationRequest(
                players =
                    mapOf(
                        "P1" to PlayerProfile(playerId = "P1", name = "Player 1", rating = Rating("10.000", RatingSystem.UTR)),
                        "P2" to PlayerProfile(playerId = "P2", name = "Player 2", rating = Rating("10.009", RatingSystem.UTR)),
                    ),
                matchScore =
                    MatchScore(
                        sets =
                            listOf(
                                SetScore(games = mapOf("P1" to 6, "P2" to 4), winner = "P1"),
                            ),
                    ),
            )

        val result = calculator.calculate(request)

        // With diff < 0.01, should use "equal ratings" case
        // Winner should gain rating, loser should lose
        val p1Change = result.response.ratingChanges["P1"]!!.change.toBigDecimal()
        val p2Change = result.response.ratingChanges["P2"]!!.change.toBigDecimal()

        p1Change shouldBeGreaterThan "0.0".toBigDecimal()
        p2Change shouldBeLessThan "0.0".toBigDecimal()

        // Should be zero-sum
        (p1Change + p2Change).abs() shouldBeLessThan "0.0001".toBigDecimal()
    }

    @Test
    fun `testNearEqualRatings_AboveThreshold - 0-011 difference NOT treated as equal`() {
        // Edge case: Rating diff = 0.011, above 0.01 threshold → NOT treated as equal
        val request =
            RankingCalculationRequest(
                players =
                    mapOf(
                        "P1" to PlayerProfile(playerId = "P1", name = "Player 1", rating = Rating("10.000", RatingSystem.UTR)),
                        "P2" to PlayerProfile(playerId = "P2", name = "Player 2", rating = Rating("10.011", RatingSystem.UTR)),
                    ),
                matchScore =
                    MatchScore(
                        sets =
                            listOf(
                                SetScore(games = mapOf("P1" to 6, "P2" to 4), winner = "P1"),
                            ),
                    ),
            )

        val result = calculator.calculate(request)

        // With diff >= 0.01, should use performance-based adjustments
        // P1 (lower rated) won, so this is an upset → capped changes
        val p1Change = result.response.ratingChanges["P1"]!!.change.toBigDecimal()
        val p2Change = result.response.ratingChanges["P2"]!!.change.toBigDecimal()

        p1Change shouldBeGreaterThan "0.0".toBigDecimal()
        p2Change shouldBeLessThan "0.0".toBigDecimal()

        // Should be zero-sum
        (p1Change + p2Change).abs() shouldBeLessThan "0.0001".toBigDecimal()
    }

    @Test
    fun `testOverperformMarginDiff_ExactlyTwoGames - threshold boundary`() {
        // Edge case: Margin difference exactly 2.0 games triggers full cap
        // P1 (5.0) vs P2 (4.0), ratingDiff = 1.0
        // Expected margin = 1.0 × 6 = 6 games
        // P1 wins 6-0, 6-2 = 8 games margin
        // Margin diff = 8 - 6 = 2.0 → full cap
        val request =
            RankingCalculationRequest(
                players =
                    mapOf(
                        "P1" to PlayerProfile(playerId = "P1", name = "Player 1", rating = Rating("5.0", RatingSystem.NTRP)),
                        "P2" to PlayerProfile(playerId = "P2", name = "Player 2", rating = Rating("4.0", RatingSystem.NTRP)),
                    ),
                matchScore =
                    MatchScore(
                        sets =
                            listOf(
                                SetScore(games = mapOf("P1" to 6, "P2" to 0), winner = "P1"),
                                SetScore(games = mapOf("P1" to 6, "P2" to 2), winner = "P1"),
                            ),
                    ),
            )

        val result = calculator.calculate(request)

        // Expected: Full cap = ratingDiff/3 = 1.0/3 = 0.333333
        val p1Change = result.response.ratingChanges["P1"]!!.change.toBigDecimal()
        p1Change shouldBe "0.333333".toBigDecimal()

        val p2Change = result.response.ratingChanges["P2"]!!.change.toBigDecimal()
        p2Change shouldBe "-0.333333".toBigDecimal()
    }

    @Test
    fun `testBestOfFiveMatch - professional 5-set match`() {
        // Edge case: Best-of-5 match (Grand Slam format)
        // Algorithm should handle any number of sets
        val request =
            RankingCalculationRequest(
                players =
                    mapOf(
                        "PRO1" to PlayerProfile(playerId = "PRO1", name = "Pro 1", rating = Rating("13.5", RatingSystem.UTR)),
                        "PRO2" to PlayerProfile(playerId = "PRO2", name = "Pro 2", rating = Rating("13.0", RatingSystem.UTR)),
                    ),
                matchScore =
                    MatchScore(
                        sets =
                            listOf(
                                SetScore(games = mapOf("PRO1" to 6, "PRO2" to 4), winner = "PRO1"),
                                SetScore(games = mapOf("PRO1" to 4, "PRO2" to 6), winner = "PRO2"),
                                SetScore(games = mapOf("PRO1" to 6, "PRO2" to 3), winner = "PRO1"),
                                SetScore(games = mapOf("PRO1" to 4, "PRO2" to 6), winner = "PRO2"),
                                SetScore(games = mapOf("PRO1" to 6, "PRO2" to 2), winner = "PRO1"),
                            ),
                    ),
            )

        val result = calculator.calculate(request)

        // Verify calculation completes successfully
        // Total games: PRO1 = 26, PRO2 = 21, dominance = 26/21 = 1.238
        val pro1Change = result.response.ratingChanges["PRO1"]!!.change.toBigDecimal()
        val pro2Change = result.response.ratingChanges["PRO2"]!!.change.toBigDecimal()

        // Higher rated player won, should gain some rating
        pro1Change shouldBeGreaterThan "0.0".toBigDecimal()
        pro2Change shouldBeLessThan "0.0".toBigDecimal()

        // Should be zero-sum
        (pro1Change + pro2Change).abs() shouldBeLessThan "0.0001".toBigDecimal()
    }

    @Test
    fun `testMultipleTiebreaks_ThreeSets - tiebreaks in sets 1 and 3`() {
        // Edge case: Multiple tiebreak sets in a match
        // Set 1: 6-7 tiebreak (P2 wins), Set 2: 6-3 (P1 wins), Set 3: 7-6 tiebreak (P1 wins)
        val request =
            RankingCalculationRequest(
                players =
                    mapOf(
                        "P1" to PlayerProfile(playerId = "P1", name = "Player 1", rating = Rating("10.0", RatingSystem.UTR)),
                        "P2" to PlayerProfile(playerId = "P2", name = "Player 2", rating = Rating("9.8", RatingSystem.UTR)),
                    ),
                matchScore =
                    MatchScore(
                        sets =
                            listOf(
                                SetScore(
                                    games = mapOf("P1" to 6, "P2" to 7),
                                    winner = "P2",
                                    tiebreak = TiebreakScore(points = mapOf("P1" to 5, "P2" to 7), winner = "P2"),
                                ),
                                SetScore(games = mapOf("P1" to 6, "P2" to 3), winner = "P1"),
                                SetScore(
                                    games = mapOf("P1" to 7, "P2" to 6),
                                    winner = "P1",
                                    tiebreak = TiebreakScore(points = mapOf("P1" to 7, "P2" to 5), winner = "P1"),
                                ),
                            ),
                    ),
            )

        val result = calculator.calculate(request)

        // Total games: P1 = 19, P2 = 16, dominance = 19/16 = 1.1875
        // Very close match, changes should be small
        val p1Change = result.response.ratingChanges["P1"]!!.change.toBigDecimal()
        val p2Change = result.response.ratingChanges["P2"]!!.change.toBigDecimal()

        // P1 (higher rated) won a close match
        // Might result in rating loss due to underperformance
        // (Expected margin with 0.2 diff = 1.2 games, actual = 3 games)
        p1Change shouldNotBe "0.0".toBigDecimal()
        p2Change shouldNotBe "0.0".toBigDecimal()

        // Should be zero-sum
        (p1Change + p2Change).abs() shouldBeLessThan "0.0001".toBigDecimal()
    }

    @Test
    fun `testSplitSetsVaryingMargins - comeback win with varied set scores`() {
        // Edge case: Split sets with very different margins
        // P1 wins 6-0 (dominant), loses 3-6 (weak), wins 6-2 (strong)
        val request =
            RankingCalculationRequest(
                players =
                    mapOf(
                        "P1" to PlayerProfile(playerId = "P1", name = "Player 1", rating = Rating("5.0", RatingSystem.NTRP)),
                        "P2" to PlayerProfile(playerId = "P2", name = "Player 2", rating = Rating("4.8", RatingSystem.NTRP)),
                    ),
                matchScore =
                    MatchScore(
                        sets =
                            listOf(
                                SetScore(games = mapOf("P1" to 6, "P2" to 0), winner = "P1"),
                                SetScore(games = mapOf("P1" to 3, "P2" to 6), winner = "P2"),
                                SetScore(games = mapOf("P1" to 6, "P2" to 2), winner = "P1"),
                            ),
                    ),
            )

        val result = calculator.calculate(request)

        // Total games: P1 = 15, P2 = 8, dominance = 15/8 = 1.875
        // Expected margin with 0.2 diff = 0.2 × 6 = 1.2 games
        // Actual margin = 15 - 8 = 7 games
        // Margin diff = 5.8 games → overperformance, should cap changes
        val p1Change = result.response.ratingChanges["P1"]!!.change.toBigDecimal()
        val p2Change = result.response.ratingChanges["P2"]!!.change.toBigDecimal()

        // P1 should gain (higher rated player won decisively)
        p1Change shouldBeGreaterThan "0.0".toBigDecimal()
        p2Change shouldBeLessThan "0.0".toBigDecimal()

        // Should be zero-sum
        (p1Change + p2Change).abs() shouldBeLessThan "0.0001".toBigDecimal()
    }

    @Test
    fun `testZeroGamesLoser_Shutout - dominance defaults to 2-0`() {
        // Edge case: Complete shutout (6-0, 6-0)
        // Loser won 0 games → dominance defaults to 2.0 instead of undefined
        val request =
            RankingCalculationRequest(
                players =
                    mapOf(
                        "STRONG" to
                            PlayerProfile(
                                playerId = "STRONG",
                                name = "Strong",
                                rating = Rating("5.0", RatingSystem.NTRP),
                            ),
                        "WEAK" to PlayerProfile(playerId = "WEAK", name = "Weak", rating = Rating("4.0", RatingSystem.NTRP)),
                    ),
                matchScore =
                    MatchScore(
                        sets =
                            listOf(
                                SetScore(games = mapOf("STRONG" to 6, "WEAK" to 0), winner = "STRONG"),
                                SetScore(games = mapOf("STRONG" to 6, "WEAK" to 0), winner = "STRONG"),
                            ),
                    ),
            )

        val result = calculator.calculate(request)

        // Verify calculation completes (doesn't divide by zero)
        // Expected: Capped change due to overperformance
        val strongChange = result.response.ratingChanges["STRONG"]!!.change.toBigDecimal()
        val weakChange = result.response.ratingChanges["WEAK"]!!.change.toBigDecimal()

        strongChange shouldBeGreaterThan "0.0".toBigDecimal()
        weakChange shouldBeLessThan "0.0".toBigDecimal()

        // Should be zero-sum
        (strongChange + weakChange).abs() shouldBeLessThan "0.0001".toBigDecimal()
    }

    @Test
    fun `testBoundaryPlayers_ExtremeGap - large gap with boundary players`() {
        // Edge case: Both players at rating boundaries
        // 7.0 (max NTRP) vs 1.0 (min NTRP) - theoretical edge case
        val request =
            RankingCalculationRequest(
                players =
                    mapOf(
                        "MAX" to PlayerProfile(playerId = "MAX", name = "Max", rating = Rating("7.0", RatingSystem.NTRP)),
                        "MIN" to PlayerProfile(playerId = "MIN", name = "Min", rating = Rating("1.0", RatingSystem.NTRP)),
                    ),
                matchScore =
                    MatchScore(
                        sets =
                            listOf(
                                SetScore(games = mapOf("MAX" to 6, "MIN" to 3), winner = "MAX"),
                            ),
                    ),
            )

        val result = calculator.calculate(request)

        // Expected: Large rating gap (6.0) causes margin capping
        // uncappedExpectedMargin = 6.0 × 6 = 36 games
        // maxPossibleMargin = 6 games
        // expectedMargin = 6 (capped)
        // actualMargin = 3 games
        // This is underperformance (actual < expected)
        // MAX should lose rating, MIN should gain rating

        // Verify both players can move from boundaries (underperformance allows this)
        val maxChange = result.response.ratingChanges["MAX"]!!.change.toBigDecimal()
        val minChange = result.response.ratingChanges["MIN"]!!.change.toBigDecimal()

        // MAX should lose rating for underperforming
        maxChange shouldBeLessThan "0.0".toBigDecimal()
        // MIN should gain rating
        minChange shouldBeGreaterThan "0.0".toBigDecimal()

        // Should be zero-sum (not at actual boundaries after calculation)
        (maxChange + minChange).abs() shouldBeLessThan "0.0001".toBigDecimal()
    }

    @Test
    fun `testModerateOverperformance_BelowTwoGames - reduced cap applied`() {
        // Edge case: Margin diff < 2.0 (should apply 0.798846 factor)
        //
        // To get margin diff < 2.0, we need:
        // P1 (5.0) vs P2 (4.5), ratingDiff = 0.5
        // Expected margin = 0.5 × 6 = 3.0 games
        // P1 wins 6-4 = total 6 games vs 4 games
        // Actual margin = 2 games
        // Margin diff = 2 - 3 = -1 (underperformance, not overperformance)
        //
        // Try different scenario:
        // P1 (5.0) vs P2 (4.0), ratingDiff = 1.0
        // Expected margin = 1.0 × 6 = 6.0 games
        // P1 wins 6-2, 6-1 = 12 vs 3 games
        // Actual margin = 9 games
        // Margin diff = 9 - 6 = 3 games → full cap since >= 2.0
        //
        // Actually, use a smaller rating gap:
        // P1 (5.0) vs P2 (4.8), ratingDiff = 0.2
        // Expected margin = 0.2 × 6 = 1.2 games
        // P1 wins 6-3 = 6 vs 3 games
        // Actual margin = 3 games
        // Margin diff = 3 - 1.2 = 1.8 games < 2.0 ✓
        val request =
            RankingCalculationRequest(
                players =
                    mapOf(
                        "P1" to PlayerProfile(playerId = "P1", name = "Player 1", rating = Rating("5.0", RatingSystem.NTRP)),
                        "P2" to PlayerProfile(playerId = "P2", name = "Player 2", rating = Rating("4.8", RatingSystem.NTRP)),
                    ),
                matchScore =
                    MatchScore(
                        sets =
                            listOf(
                                SetScore(games = mapOf("P1" to 6, "P2" to 3), winner = "P1"),
                            ),
                    ),
            )

        val result = calculator.calculate(request)

        // Expected cap = ratingDiff/3 = 0.2/3 = 0.066667
        // Margin diff = 1.8 < 2.0, apply 0.798846 factor
        // Expected change = 0.066667 × 0.798846 = 0.053257
        val p1Change = result.response.ratingChanges["P1"]!!.change

        // Should be capped at reduced value (rounded to 6 decimals)
        p1Change shouldBe "0.053257"
    }
}
