// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The sparsity + match-weight confidence (#459). Values are the design doc's worked examples
 * (`docs/product/RATING_CONFIDENCE_SPARSITY.md`), asserted to ~3 dp against the exact formula.
 */
class RatingConfidenceTest {
    @Test
    fun `no qualifying matches in the window yields zero confidence`() {
        confidenceAt(counts = WeightClassCounts()).toPlainString() shouldBe "0.000000"
    }

    @Test
    fun `two open-play matches (weighted count one) sit near the fifty-percent cliff`() {
        // wc = 2 * 0.5 = 1.0, gap = 30 / 1.0 = 30, (30/35)^2.5 ≈ 0.680 → ≈ 0.595.
        confidenceAt(counts = WeightClassCounts(openPlays = 2)).toDouble() shouldBe (0.595 plusOrMinus 0.001)
    }

    @Test
    fun `eight open-play matches (weighted count four) reach near-full confidence`() {
        // wc = 8 * 0.5 = 4.0, gap = 30 / 4.0 = 7.5 → ≈ 0.979.
        confidenceAt(counts = WeightClassCounts(openPlays = 8)).toDouble() shouldBe (0.979 plusOrMinus 0.001)
    }

    @Test
    fun `two tournament matches prove current form with a very tight gap`() {
        // wc = 2 * 3.0 = 6.0, gap = 30 / 6.0 = 5.0 → ≈ 0.992.
        confidenceAt(counts = WeightClassCounts(tournaments = 2)).toDouble() shouldBe (0.992 plusOrMinus 0.001)
    }

    @Test
    fun `for equal match counts a tournament outweighs a league outweighs open play`() {
        // Same count (one match) across classes; the weight ordering carries through to confidence.
        val tournament = confidenceAt(counts = WeightClassCounts(tournaments = 1))
        val league = confidenceAt(counts = WeightClassCounts(leagues = 1))
        val openPlay = confidenceAt(counts = WeightClassCounts(openPlays = 1))
        tournament shouldBeGreaterThan league
        league shouldBeGreaterThan openPlay
    }

    @Test
    fun `mixed weight classes sum before the gap is taken`() {
        // wc = 3.0 + 1.5 + 0.5 = 5.0, gap = 6.0; equals a single class with the same weighted count.
        val mixed = confidenceAt(counts = WeightClassCounts(tournaments = 1, leagues = 1, openPlays = 1))
        mixed shouldBeGreaterThan confidenceAt(counts = WeightClassCounts(openPlays = 8))
    }

    @Test
    fun `confidence rises monotonically with more play and always stays within zero and one`() {
        val one = confidenceAt(counts = WeightClassCounts(openPlays = 1))
        val four = confidenceAt(counts = WeightClassCounts(openPlays = 4))
        val twelve = confidenceAt(counts = WeightClassCounts(openPlays = 12))
        one shouldBeLessThan four
        four shouldBeLessThan twelve
        one shouldBeGreaterThan BigDecimal.ZERO
        twelve shouldBeLessThan BigDecimal.ONE
    }

    @Test
    fun `the weight-class mapping folds playoffs into their parent class`() {
        MatchType.TOURNAMENT_INITIAL_ROUND.weightClass() shouldBe WeightClass.TOURNAMENT
        MatchType.TOURNAMENT_PLAYOFFS.weightClass() shouldBe WeightClass.TOURNAMENT
        MatchType.LEAGUE_PLAY.weightClass() shouldBe WeightClass.LEAGUE
        MatchType.LEAGUE_PLAYOFFS.weightClass() shouldBe WeightClass.LEAGUE
        MatchType.OPEN_PLAY.weightClass() shouldBe WeightClass.OPEN_PLAY
    }
}
