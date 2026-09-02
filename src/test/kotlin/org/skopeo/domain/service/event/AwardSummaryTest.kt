// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.event

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [EventFinalizeAwarder.AwardSummary.combinedWith] in isolation. A tournament finalize runs two payout
 * halves — placement and per-set (#836) — and reports one summary, so the merge has to be right or the
 * audit trail under-counts a payout that did happen.
 */
class AwardSummaryTest {
    private fun summary(
        matches: Int,
        awards: Int,
        points: String,
        suppressed: Boolean = false,
    ) = EventFinalizeAwarder.AwardSummary(
        matchCount = matches,
        awardCount = awards,
        totalPoints = BigDecimal(points),
        suppressedByGlobalFlag = suppressed,
    )

    @Test
    fun `counts and totals add across the two halves`() {
        val merged =
            summary(matches = 2, awards = 4, points = "1800")
                .combinedWith(other = summary(matches = 5, awards = 10, points = "137"))
        merged.matchCount shouldBe 7
        merged.awardCount shouldBe 14
        merged.totalPoints shouldBe BigDecimal("1937")
    }

    @Test
    fun `suppression is sticky, so a half that paid nothing cannot mask it`() {
        // The flag must survive the merge from EITHER side. In practice the global kill switch (#752)
        // short-circuits before either half runs, so this is defensive — but if it ever reached the merge,
        // reporting "paid out" because the other half was unsuppressed would tell a host the opposite of
        // the truth.
        summary(matches = 0, awards = 0, points = "0", suppressed = true)
            .combinedWith(other = summary(matches = 1, awards = 2, points = "10"))
            .suppressedByGlobalFlag shouldBe true
        summary(matches = 1, awards = 2, points = "10")
            .combinedWith(other = summary(matches = 0, awards = 0, points = "0", suppressed = true))
            .suppressedByGlobalFlag shouldBe true
    }

    @Test
    fun `two unsuppressed halves stay unsuppressed`() {
        summary(matches = 1, awards = 2, points = "10")
            .combinedWith(other = summary(matches = 1, awards = 2, points = "10"))
            .suppressedByGlobalFlag shouldBe false
    }
}
