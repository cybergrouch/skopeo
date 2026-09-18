// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.model

import org.skopeo.common.security.Capability
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
    // Restrict to users holding this capability (#317) — e.g. CLUB_OWNER for the club-owner picker.
    val capability: Capability? = null,
    // Restrict to one lifecycle state (#1050). Note MERGED and DELETED are inactive by definition, so
    // asking for either with `includeInactive = false` is a contradiction the service rejects rather
    // than silently answering with an empty page.
    val status: AccountStatus? = null,
    // Whether the player's rating is still calibrating (#1065). Queryable at last because #1051 stores
    // the rated-match count: the verdict is one comparison against the live global N, so this is a plain
    // WHERE rather than the aggregate #1050 had to defer. Null means "don't filter on it".
    val inCalibration: Boolean? = null,
) {
    companion object {
        /** The facet names, for the "at least one filter" error (#116) — kept beside the fields they name. */
        const val FACET_NAMES = "name, code, q, sex, age, rating, capability, status, inCalibration"
    }
}

/**
 * Does this query narrow anything at all (#116)? A search with no facet would return the whole member
 * table, so it is refused.
 *
 * Lives here, next to the fields, so that adding a facet and forgetting to count it is one edit rather
 * than two files apart — the service checks the built query instead of tracking its own locals.
 */
fun UserSearchQuery.hasAnyFacet(): Boolean =
    name != null || code != null || q != null || sex != null ||
        dobMin != null || dobMax != null || rating != null ||
        capability != null || status != null || inCalibration != null

/**
 * The columns a user search may be ordered by (#1050).
 *
 * Seven of the Research table's ten columns. Absent on purpose:
 *  - the **icon** and **code** columns are display-only by decision;
 *  - **calibration** is still absent, but the reason has narrowed (#1051). The rated-match count is now a
 *    column on `user_ratings`, so the aggregate no longer stands in the way; what remains is that the
 *    verdict is `count < N` against a live global setting, and expressing that comparison in an ORDER BY
 *    would put a second copy of the rule in SQL — the drift #882 records. Adding it means deciding where
 *    N enters the query (bound parameter supplied by `CalibrationService`, so the rule keeps one home),
 *    which is a separate decision from the schema change and is left to the follow-up.
 *
 * Ordering is applied in the database, BEFORE paging — a sort over the current page only would reorder
 * 25 rows while `total` described the whole result set.
 */
enum class UserSearchSort { DISPLAY_NAME, LAST_NAME, FIRST_NAME, SEX, AGE, RATING, STATUS, CALIBRATION }

/** Ascending or descending, for [UserSearchSort]. */
enum class SortDirection { ASC, DESC }

/** A page of user-search results plus the total match count, for numbered pagination (#232). */
data class UserSearchPage(
    val items: List<User>,
    val total: Long,
)
