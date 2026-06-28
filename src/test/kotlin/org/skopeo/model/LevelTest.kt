// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class LevelTest {
    @Test
    fun `positionInBand maps a rating to its 0 to 1 spot within the 0_5-wide band (#114)`() {
        // Floor of the band → 0.0; midpoint → 0.5; just under the ceiling → near 1.0.
        Level.positionInBand(rating = BigDecimal("4.0")) shouldBe 0.0
        Level.positionInBand(rating = BigDecimal("4.25")) shouldBe 0.5
        Level.positionInBand(rating = BigDecimal("4.49")).let {
            it shouldBeGreaterThan 0.9
            it shouldBeLessThan 1.0
        }
        // Crossing into the next band resets to that band's floor.
        Level.positionInBand(rating = BigDecimal("4.5")) shouldBe 0.0
    }

    @Test
    fun `positionInBand clamps the open-ended 7_0 band and the bottom of the scale`() {
        // 7.0 is open-ended: a 0.5-wide window above it is full scale, beyond clamps to 1.0.
        Level.positionInBand(rating = BigDecimal("7.0")) shouldBe 0.0
        Level.positionInBand(rating = BigDecimal("7.25")) shouldBe 0.5
        Level.positionInBand(rating = BigDecimal("8.0")) shouldBe 1.0
        // Below the 1.0 floor clamps to the 1.0 band floor → 0.0.
        Level.positionInBand(rating = BigDecimal("0.5")) shouldBe 0.0
    }
}
