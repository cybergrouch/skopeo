// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class RatingConfidenceTest {
    private val now = LocalDateTime.of(2026, 7, 1, 12, 0)

    @Test
    fun `a rating with no match-calc timestamp has zero confidence`() {
        confidenceAt(matchRatedAt = null, matchesSinceReset = 5, now = now).toPlainString() shouldBe "0.000000"
    }

    @Test
    fun `a fresh, fully-ramped rating is at full confidence`() {
        // 0 days since the calc (decay = 1.0), 5+ matches (scale = 1.0).
        confidenceAt(matchRatedAt = now, matchesSinceReset = 5, now = now).toPlainString() shouldBe "1.000000"
    }

    @Test
    fun `the time decay is one half around the 35-day half point`() {
        // decay = 1 / (1 + (35/35)^2.5) = 0.5; scale saturated so confidence tracks the decay.
        val at35Days = confidenceAt(matchRatedAt = now.minusDays(35), matchesSinceReset = 5, now = now)
        at35Days.toPlainString() shouldBe "0.500000"
    }

    @Test
    fun `confidence decays monotonically as the rating ages`() {
        val fresh = confidenceAt(matchRatedAt = now.minusDays(5), matchesSinceReset = 5, now = now)
        val older = confidenceAt(matchRatedAt = now.minusDays(50), matchesSinceReset = 5, now = now)
        val oldest = confidenceAt(matchRatedAt = now.minusDays(120), matchesSinceReset = 5, now = now)
        fresh shouldBeGreaterThan older
        older shouldBeGreaterThan oldest
    }

    @Test
    fun `the match-count ramp scales confidence up over the first five matches`() {
        // Fresh calc (decay = 1.0), so confidence == scale = min(1, m/5).
        confidenceAt(matchRatedAt = now, matchesSinceReset = 0, now = now).toPlainString() shouldBe "0.000000"
        confidenceAt(matchRatedAt = now, matchesSinceReset = 1, now = now).toPlainString() shouldBe "0.200000"
        confidenceAt(matchRatedAt = now, matchesSinceReset = 3, now = now).toPlainString() shouldBe "0.600000"
        confidenceAt(matchRatedAt = now, matchesSinceReset = 5, now = now).toPlainString() shouldBe "1.000000"
        // Beyond five matches the ramp is capped at 1.0.
        confidenceAt(matchRatedAt = now, matchesSinceReset = 12, now = now).toPlainString() shouldBe "1.000000"
    }

    @Test
    fun `just after a reset (zero matches) confidence starts at zero even when fresh`() {
        confidenceAt(matchRatedAt = now, matchesSinceReset = 0, now = now).toPlainString() shouldBe "0.000000"
    }

    @Test
    fun `a future or clock-skewed timestamp is clamped to full freshness, never above one`() {
        val skewed = confidenceAt(matchRatedAt = now.plusDays(3), matchesSinceReset = 5, now = now)
        skewed.toPlainString() shouldBe "1.000000"
    }

    @Test
    fun `confidence always stays within zero and one`() {
        val value = confidenceAt(matchRatedAt = now.minusDays(10), matchesSinceReset = 2, now = now)
        value shouldBeGreaterThan BigDecimal.ZERO
        value shouldBeLessThan BigDecimal.ONE
    }
}
