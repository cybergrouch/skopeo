// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** Pure unit test for the point-class validity policy (#146, §4.2) — no DB. */
class PointClassPolicyTest {
    private val from = LocalDateTime.of(2026, 1, 1, 12, 0)

    @Test
    fun `each point class carries its documented default validity horizon`() {
        PointClass.ANNUAL_TOURNAMENT.defaultValidUntil(from = from) shouldBe from.plusMonths(12)
        PointClass.FULL_MATCH.defaultValidUntil(from = from) shouldBe from.plusMonths(6)
        PointClass.OPEN_PLAY.defaultValidUntil(from = from) shouldBe from.plusMonths(1)
        PointClass.EXTERNAL.defaultValidUntil(from = from) shouldBe from.plusMonths(12)
    }

    @Test
    fun `every point class is covered by the horizon test above`() {
        // Guards the pair above from drifting apart: a new class added without a horizon assertion would
        // otherwise ship untested, which is how the unused SEASONAL_TOURNAMENT_* values survived so long.
        PointClass.entries.map { it.name }.toSet() shouldBe
            setOf("ANNUAL_TOURNAMENT", "FULL_MATCH", "OPEN_PLAY", "EXTERNAL")
    }

    @Test
    fun `countsAsOf is true only for an active award inside its half-open validity window`() {
        val award =
            RankingPointAward(
                id = java.util.UUID.randomUUID(),
                userId = java.util.UUID.randomUUID(),
                points = java.math.BigDecimal("100"),
                pointClass = PointClass.ANNUAL_TOURNAMENT,
                sourceType = PointSourceType.INTERNAL,
                sourceId = null,
                band = "4.0",
                sex = "Male",
                reason = null,
                validFrom = from,
                validUntil = from.plusMonths(12),
                status = AwardStatus.ACTIVE,
                revokesAwardId = null,
                grantedBy = null,
                awardedAt = from,
            )

        // Inside the window counts; the exact start counts (inclusive); the exact end does not (exclusive).
        award.countsAsOf(asOf = from.plusMonths(6)) shouldBe true
        award.countsAsOf(asOf = from) shouldBe true
        award.countsAsOf(asOf = from.plusMonths(12)) shouldBe false
        // Before the window does not count.
        award.countsAsOf(asOf = from.minusDays(1)) shouldBe false
        // A revoked award never counts, even mid-window.
        award.copy(status = AwardStatus.REVOKED).countsAsOf(asOf = from.plusMonths(6)) shouldBe false
    }
}
