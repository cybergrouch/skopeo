// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class SearchRangesTest {
    @Test
    fun `parses inclusive and exclusive bounds`() {
        NumericRange.parse(raw = "[3.0,4.0)") shouldBe
            NumericRange(
                lower = RangeBound(value = BigDecimal("3.0"), inclusive = true),
                upper = RangeBound(value = BigDecimal("4.0"), inclusive = false),
            )
        NumericRange.parse(raw = "(20,30]") shouldBe
            NumericRange(
                lower = RangeBound(value = BigDecimal("20"), inclusive = false),
                upper = RangeBound(value = BigDecimal("30"), inclusive = true),
            )
    }

    @Test
    fun `parses open-ended bounds`() {
        NumericRange.parse(raw = "[3.0,)").upper.shouldBeNull()
        NumericRange.parse(raw = "(,30]").lower.shouldBeNull()
    }

    @Test
    fun `rejects malformed ranges`() {
        shouldThrow<IllegalArgumentException> { NumericRange.parse(raw = "3.0,4.0") } // no brackets
        shouldThrow<IllegalArgumentException> { NumericRange.parse(raw = "[3.0]") } // no comma
        shouldThrow<IllegalArgumentException> { NumericRange.parse(raw = "[a,4.0]") } // bad number
        shouldThrow<IllegalArgumentException> { NumericRange.parse(raw = "[,]") } // no bounds
        shouldThrow<IllegalArgumentException> { NumericRange.parse(raw = "(4.0,3.0)") } // lower > upper
        shouldThrow<IllegalArgumentException> { NumericRange.parse(raw = "[1,2,3]") } // two commas
    }

    private val today = LocalDate.of(2026, 6, 23)

    @Test
    fun `maps an inclusive age range to a date-of-birth window`() {
        val window = ageRangeToDob(range = NumericRange.parse(raw = "[20,30]"), today = today)
        window.max shouldBe LocalDate.of(2006, 6, 23) // age >= 20
        window.min shouldBe LocalDate.of(1995, 6, 24) // age <= 30
    }

    @Test
    fun `maps an exclusive age range to a date-of-birth window`() {
        val window = ageRangeToDob(range = NumericRange.parse(raw = "(20,30)"), today = today)
        window.max shouldBe LocalDate.of(2005, 6, 23) // age > 20
        window.min shouldBe LocalDate.of(1996, 6, 24) // age < 30
    }

    @Test
    fun `maps open-ended age ranges`() {
        ageRangeToDob(range = NumericRange.parse(raw = "[20,)"), today = today).min.shouldBeNull()
        ageRangeToDob(range = NumericRange.parse(raw = "(,30]"), today = today).max.shouldBeNull()
    }

    @Test
    fun `rejects fractional age bounds`() {
        shouldThrow<IllegalArgumentException> { ageRangeToDob(range = NumericRange.parse(raw = "[20.5,30]"), today = today) }
    }
}
