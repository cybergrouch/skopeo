// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.math.BigDecimal
import java.time.LocalDate

private const val MIN_RANGE_LENGTH = 3 // e.g. "[,]" — brackets + comma
private const val RANGE_PARTS = 2

/** One end of an interval: its [value] and whether the bound is [inclusive]. */
data class RangeBound(
    val value: BigDecimal,
    val inclusive: Boolean,
)

/**
 * A parsed numeric interval. Either end may be null (unbounded). Interval notation:
 * '[' / ']' are inclusive, '(' / ')' are exclusive — so "[3.0,4.0)" is 3.0 ≤ x < 4.0 and
 * "(20,30]" is 20 < x ≤ 30. An empty side ("[3.0,)") is unbounded on that side.
 */
data class NumericRange(
    val lower: RangeBound?,
    val upper: RangeBound?,
) {
    companion object {
        fun parse(raw: String): NumericRange {
            val text = raw.trim()
            require(value = text.length >= MIN_RANGE_LENGTH) { "Invalid range '$raw'" }
            val open = text.first()
            val close = text.last()
            require(value = open == '[' || open == '(') { "Range '$raw' must start with '[' or '('" }
            require(value = close == ']' || close == ')') { "Range '$raw' must end with ']' or ')'" }

            val parts = text.substring(startIndex = 1, endIndex = text.length - 1).split(",")
            require(value = parts.size == RANGE_PARTS) { "Range '$raw' must have exactly one comma" }

            val lower = boundOf(part = parts[0], inclusive = open == '[', raw = raw)
            val upper = boundOf(part = parts[1], inclusive = close == ']', raw = raw)
            require(value = lower != null || upper != null) { "Range '$raw' must bound at least one side" }
            if (lower != null && upper != null) {
                require(value = lower.value <= upper.value) { "Range '$raw' lower bound exceeds upper" }
            }
            return NumericRange(lower = lower, upper = upper)
        }

        private fun boundOf(
            part: String,
            inclusive: Boolean,
            raw: String,
        ): RangeBound? {
            val value = part.trim().ifEmpty { return null }
            val number =
                value.toBigDecimalOrNull()
                    ?: throw IllegalArgumentException("Invalid number '$value' in range '$raw'")
            return RangeBound(value = number, inclusive = inclusive)
        }
    }
}

/** The inclusive date-of-birth window (either end nullable) matching an integer-age range as of [today]. */
data class DobWindow(
    val min: LocalDate?,
    val max: LocalDate?,
)

/**
 * Convert an integer-age [range] to the date-of-birth window that satisfies it, relative to
 * [today]. A lower age bound caps the latest birth date (you must be at least that old); an
 * upper age bound sets the earliest. Age bounds must be whole numbers.
 */
fun ageRangeToDob(
    range: NumericRange,
    today: LocalDate,
): DobWindow {
    val max =
        range.lower?.let { bound ->
            val low = wholeAge(value = bound.value)
            // age ≥ low ⟹ dob ≤ today−low years; age > low ⟹ dob ≤ today−(low+1) years.
            today.minusYears((if (bound.inclusive) low else low + 1).toLong())
        }
    val min =
        range.upper?.let { bound ->
            val high = wholeAge(value = bound.value)
            // age ≤ high ⟹ dob ≥ today−(high+1) years +1 day; age < high ⟹ dob ≥ today−high years +1 day.
            today.minusYears((if (bound.inclusive) high + 1 else high).toLong()).plusDays(1)
        }
    return DobWindow(min = min, max = max)
}

private fun wholeAge(value: BigDecimal): Int {
    require(value = value.stripTrailingZeros().scale() <= 0) { "Age bounds must be whole numbers, got $value" }
    return value.toInt()
}

/**
 * Resolved repository-level user search: any non-null facet is an AND filter. [q] is the unified
 * picker term — it matches a fuzzy name OR a player-code prefix in a single OR clause (issue #86),
 * so typing either surfaces players incrementally without the client having to guess.
 */
data class UserSearchQuery(
    val name: String?,
    val code: String?,
    val q: String?,
    val sex: String?,
    val dobMin: LocalDate?,
    val dobMax: LocalDate?,
    val rating: NumericRange?,
)
