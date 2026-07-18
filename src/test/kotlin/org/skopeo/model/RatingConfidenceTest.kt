// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.pow

/**
 * The 3-factor recency × sparsity × spacing confidence (#459). Values are the design doc's worked
 * examples (`docs/product/RATING_CONFIDENCE_SPARSITY.md`), asserted to ~3 dp against the exact formula
 * `f(x) = 1 / (1 + (x / 35)^2.5)`. Every case is evaluated as of a fixed [NOW]; the window is 30 days.
 */
class RatingConfidenceTest {
    private companion object {
        // A fixed evaluation instant; "day N" below means the Nth day of the window, and eval is on day 30.
        val NOW: LocalDateTime = LocalDateTime.of(2026, 6, 30, 12, 0)
        val DAY_ZERO: LocalDate = LocalDate.of(2026, 5, 31) // day N = DAY_ZERO + N; day 30 == NOW's date.
    }

    private fun openPlayOn(vararg days: Int): List<WindowMatch> =
        days.map { WindowMatch(matchDate = DAY_ZERO.plusDays(it.toLong()), weightClass = WeightClass.OPEN_PLAY) }

    @Test
    fun `no matches in the window yields zero confidence`() {
        confidenceAt(matches = emptyList(), now = NOW).toPlainString() shouldBe "0.000000"
    }

    @Test
    fun `scenario A two open play on day one and day thirty scores about thirty-seven percent`() {
        // Recency f(0)=1.0 · Sparsity f(30/1.0=30)=0.595169 · Spacing f(29)=0.615353 → ≈ 0.366289.
        confidenceAt(matches = openPlayOn(1, 30), now = NOW).toDouble() shouldBe (0.366289 plusOrMinus 0.001)
    }

    @Test
    fun `six evenly spread open play matches score about ninety-five percent`() {
        // Recency f(0)=1.0 · Sparsity f(30/3.0=10)=0.958179 · Spacing f(5)=0.992345 → ≈ 0.950848.
        confidenceAt(matches = openPlayOn(5, 10, 15, 20, 25, 30), now = NOW).toDouble() shouldBe (0.950848 plusOrMinus 0.001)
    }

    @Test
    fun `six clustered matches ending today are penalized for the internal hole`() {
        // Recency f(0)=1.0 · Sparsity f(10)=0.958179 · Spacing f(25)=0.698708 → ≈ 0.669485.
        confidenceAt(matches = openPlayOn(1, 2, 3, 4, 5, 30), now = NOW).toDouble() shouldBe (0.669485 plusOrMinus 0.001)
    }

    @Test
    fun `five clustered matches then a quiet stretch are penalized mainly by recency`() {
        // Recency f(25)=0.698708 · Sparsity f(30/2.5=12)=0.935703 · Spacing f(1)=~0.9999 → ≈ 0.653732.
        confidenceAt(matches = openPlayOn(1, 2, 3, 4, 5), now = NOW).toDouble() shouldBe (0.653732 plusOrMinus 0.001)
    }

    @Test
    fun `a single match has no internal gap so spacing is one`() {
        // One match: Spacing = 1.0, so confidence = Recency × Sparsity. On day 30: f(0)=1.0 · f(30/0.5=60).
        val single = openPlayOn(30)
        val recencyTimesSparsity = 1.0 / (1.0 + (60.0 / 35.0).pow(x = 2.5))
        confidenceAt(matches = single, now = NOW).toDouble() shouldBe (recencyTimesSparsity plusOrMinus 0.001)
    }

    @Test
    fun `for equal counts spacing and recency a tournament outweighs a league outweighs open play`() {
        // Two matches on the same two dates across classes; only the weighted count (density) differs.
        fun twoOf(weightClass: WeightClass) =
            listOf(
                WindowMatch(matchDate = DAY_ZERO.plusDays(25), weightClass = weightClass),
                WindowMatch(matchDate = DAY_ZERO.plusDays(30), weightClass = weightClass),
            )
        val tournament = confidenceAt(matches = twoOf(weightClass = WeightClass.TOURNAMENT), now = NOW)
        val league = confidenceAt(matches = twoOf(weightClass = WeightClass.LEAGUE), now = NOW)
        val openPlay = confidenceAt(matches = twoOf(weightClass = WeightClass.OPEN_PLAY), now = NOW)
        tournament shouldBeGreaterThan league
        league shouldBeGreaterThan openPlay
    }

    @Test
    fun `even spacing beats clustered spacing for the same count and recency`() {
        // Both end on day 30 (recency f(0)) with the same weighted count; only the internal hole differs.
        val even = confidenceAt(matches = openPlayOn(5, 10, 15, 20, 25, 30), now = NOW)
        val clustered = confidenceAt(matches = openPlayOn(1, 2, 3, 4, 5, 30), now = NOW)
        even shouldBeGreaterThan clustered
    }

    @Test
    fun `confidence rises with more play and always stays within zero and one`() {
        val one = confidenceAt(matches = openPlayOn(30), now = NOW)
        val four = confidenceAt(matches = openPlayOn(10, 20, 25, 30), now = NOW)
        val many = confidenceAt(matches = openPlayOn(5, 10, 15, 20, 25, 30), now = NOW)
        one shouldBeLessThan four
        four shouldBeLessThan many
        one shouldBeGreaterThan BigDecimal.ZERO
        many shouldBeLessThan BigDecimal.ONE
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
