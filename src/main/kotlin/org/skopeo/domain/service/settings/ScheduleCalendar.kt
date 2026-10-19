// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.settings

import org.skopeo.domain.model.ScheduleDate
import org.skopeo.domain.model.ScheduleSpan
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** Days per month on a leap-year calendar (`maxLength`), so Feb 29 has a slot of its own. */
private val DAYS_IN_MONTH = Month.entries.map { it.maxLength() }

private val MONTH_NAMES = Month.entries.map { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }

private const val FEBRUARY = 2
private const val FEB_28 = 28

/**
 * Yearless calendar arithmetic for the AUTO theme schedule (#1196). Every (month, day) maps to a slot on a
 * 366-day leap-year calendar (Jan 1 = 0, Feb 29 = 59, Dec 31 = 365), so a range is a set of slots and
 * overlap and gap checks are set operations. The web's `themeSchedule.ts` applies the same rules.
 */
internal object ScheduleCalendar {
    /** Slots on the leap-year calendar. */
    const val SLOTS = 366

    /** Feb 29's slot. */
    val FEB_29_SLOT = slotOf(date = ScheduleDate(month = FEBRUARY, day = 29))

    /** The slots in calendar order, skipping Feb 29: the order the gap check walks. */
    private val GAP_ORDER = (0 until SLOTS).filter { it != FEB_29_SLOT }

    /** True when [date] exists in some year: month 1–12 and a day that month can have (Feb 29 included). */
    fun isValid(date: ScheduleDate): Boolean = date.month in 1..DAYS_IN_MONTH.size && date.day in 1..DAYS_IN_MONTH[date.month - 1]

    /** The slot of a valid [date]. */
    fun slotOf(date: ScheduleDate): Int = DAYS_IN_MONTH.take(n = date.month - 1).sum() + date.day - 1

    /** The date a [slot] stands for. */
    fun dateOf(slot: Int): ScheduleDate {
        var month = 1
        var remaining = slot
        while (remaining >= DAYS_IN_MONTH[month - 1]) {
            remaining -= DAYS_IN_MONTH[month - 1]
            month += 1
        }
        return ScheduleDate(month = month, day = remaining + 1)
    }

    /**
     * The slots a valid range covers. An [end] before [start] wraps past Dec 31. A range ending Feb 28 also
     * covers Feb 29, so a leap year doesn't open a one-day gap between "… – Feb 28" and "Mar 1 – …"
     * (decision 2). Wrapping is decided on the dates as written, before that extension.
     */
    fun coverage(
        start: ScheduleDate,
        end: ScheduleDate,
    ): BooleanArray {
        val from = slotOf(date = start)
        val written = slotOf(date = end)
        val to = if (end.month == FEBRUARY && end.day == FEB_28) FEB_29_SLOT else written
        val covered = BooleanArray(size = SLOTS)
        if (written >= from) {
            for (slot in from..to) covered[slot] = true
        } else {
            for (slot in from until SLOTS) covered[slot] = true
            for (slot in 0..to) covered[slot] = true
        }
        return covered
    }

    /** The runs of slots [covered] marks, in calendar order, with a run across Dec 31 → Jan 1 kept whole. */
    fun spansOf(covered: BooleanArray): List<ScheduleSpan> = runs(order = (0 until SLOTS).toList(), member = { covered[it] })

    /**
     * The runs of dates no slot in [covered] reaches, ignoring Feb 29: a date that only exists every fourth
     * year is never reported on its own (decision 2).
     */
    fun gapsOf(covered: BooleanArray): List<ScheduleSpan> = runs(order = GAP_ORDER, member = { !covered[it] })

    /** "Apr 16" (or "Apr 31" — the day is shown as given); "month 13, day 5" when there is no such month. */
    fun format(date: ScheduleDate): String =
        if (date.month in 1..MONTH_NAMES.size) "${MONTH_NAMES[date.month - 1]} ${date.day}" else "month ${date.month}, day ${date.day}"

    /** "Apr 16 – Jun 10", or a single date when the span is one day. */
    fun format(span: ScheduleSpan): String =
        if (span.start == span.end) format(date = span.start) else "${format(date = span.start)} – ${format(date = span.end)}"

    /**
     * Maximal runs of consecutive [member] entries of [order], treated as a circle: a run touching both ends
     * is one run (it crosses New Year) and is listed last, where its start falls.
     */
    private fun runs(
        order: List<Int>,
        member: (Int) -> Boolean,
    ): List<ScheduleSpan> {
        // Each run is (first index, last index) into [order].
        val found = mutableListOf<Pair<Int, Int>>()
        var runStart: Int? = null
        order.forEachIndexed { index, slot ->
            if (member(slot)) {
                if (runStart == null) runStart = index
            } else {
                runStart?.let { found += it to index - 1 }
                runStart = null
            }
        }
        runStart?.let { found += it to order.size - 1 }
        val wraps = found.size > 1 && found.first().first == 0 && found.last().second == order.size - 1
        val merged = if (wraps) found.drop(n = 1).dropLast(n = 1) + (found.last().first to found.first().second) else found
        return merged.map { (first, last) -> ScheduleSpan(start = dateOf(slot = order[first]), end = dateOf(slot = order[last])) }
    }
}
