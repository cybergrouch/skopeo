// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.settings

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.skopeo.common.error.ServiceError
import org.skopeo.common.error.ValidationProblem
import org.skopeo.domain.model.ScheduleDate
import org.skopeo.domain.model.ScheduleSpan
import org.skopeo.domain.model.ThemeScheduleDraftRow
import org.skopeo.domain.model.ThemeScheduleRow
import org.skopeo.domain.model.ThemeSetting

/**
 * The rules of the editable AUTO theme schedule (#1196), as pure functions: [validate] turns submitted
 * rows into a schedule or the full list of save errors, and [gaps] lists the dates no row covers (a
 * warning only — those dates show Skopeo OG). The web editor checks the same rules live
 * (`web/src/lib/themeSchedule.ts`); the server is what enforces them.
 */
object ThemeScheduleRules {
    /**
     * The built-in schedule: the hard-coded rotation it replaced (#1194's table) as inclusive ranges, with
     * no gaps. V68 seeds `ui_theme_schedule` with exactly this, and it is served whenever the stored value
     * is missing or unreadable.
     */
    val DEFAULT: List<ThemeScheduleRow> = DEFAULT_THEME_SCHEDULE

    /**
     * Validate submitted [drafts] (in list order, which is resolution order). Every problem is collected —
     * per-row errors (missing name or theme, unknown theme, AUTO as a theme, an invalid date), then each set
     * of duplicate names (case-insensitive, trimmed), then every overlapping pair — and returned together as
     * one [ServiceError.Validation] whose [ServiceError.Validation.problems] list them. Otherwise the rows,
     * with names trimmed and themes canonical.
     */
    fun validate(drafts: List<ThemeScheduleDraftRow>): Either<ServiceError.Validation, List<ThemeScheduleRow>> {
        val checked = drafts.mapIndexed { index, draft -> checkRow(index = index, draft = draft) }
        val problems = checked.flatMap { it.first } + duplicates(drafts = drafts) + overlaps(drafts = drafts)
        return if (problems.isEmpty()) {
            // No problems means every row parsed, so nothing is dropped here.
            checked.mapNotNull { it.second }.right()
        } else {
            ServiceError.Validation(message = problems.joinToString(separator = "; ") { it.message }, problems = problems).left()
        }
    }

    /** The dates none of [rows] covers, in calendar order, ignoring Feb 29 on its own; empty when the year is covered. */
    fun gaps(rows: List<ThemeScheduleRow>): List<ScheduleSpan> {
        val covered = BooleanArray(size = ScheduleCalendar.SLOTS)
        rows.forEach { row ->
            ScheduleCalendar.coverage(start = row.start, end = row.end).forEachIndexed { slot, hit -> if (hit) covered[slot] = true }
        }
        return ScheduleCalendar.gapsOf(covered = covered)
    }

    /** One row's own problems, and the row itself when it has none. */
    private fun checkRow(
        index: Int,
        draft: ThemeScheduleDraftRow,
    ): Pair<List<ValidationProblem>, ThemeScheduleRow?> {
        val name = draft.name?.trim().orEmpty()
        val label = label(index = index, name = name)
        val theme = draft.theme?.trim().orEmpty()
        val parsed = parseTheme(raw = theme)
        val problems =
            listOfNotNull(
                problem(code = "MISSING_NAME", message = "$label has no name", index = index, name = name).takeIf { name.isEmpty() },
                when {
                    parsed != null -> {
                        null
                    }

                    theme.isEmpty() -> {
                        problem(code = "MISSING_THEME", message = "$label has no theme", index = index, name = name)
                    }

                    theme.equals(other = ThemeSetting.AUTO.name, ignoreCase = true) -> {
                        problem(code = "AUTO_THEME", message = "$label can't use Auto as its theme", index = index, name = name)
                    }

                    else -> {
                        problem(code = "UNKNOWN_THEME", message = "$label has an unknown theme '$theme'", index = index, name = name)
                    }
                },
                dateProblem(label = label, which = "start", date = draft.start, index = index, name = name),
                dateProblem(label = label, which = "end", date = draft.end, index = index, name = name),
            )
        val row =
            parsed?.takeIf { problems.isEmpty() }?.let {
                ThemeScheduleRow(
                    name = name,
                    theme = it,
                    start = draft.start,
                    end = draft.end,
                )
            }
        return problems to row
    }

    private fun dateProblem(
        label: String,
        which: String,
        date: ScheduleDate,
        index: Int,
        name: String,
    ): ValidationProblem? =
        problem(
            code = "INVALID_DATE",
            message = "$label has an invalid $which date (${ScheduleCalendar.format(date = date)})",
            index = index,
            name = name,
        ).takeUnless { ScheduleCalendar.isValid(date = date) }

    /** One problem per name shared by two or more rows, naming every row that shares it. */
    private fun duplicates(drafts: List<ThemeScheduleDraftRow>): List<ValidationProblem> =
        drafts
            .mapIndexed { index, draft -> index to draft.name?.trim().orEmpty() }
            .filter { (_, name) -> name.isNotEmpty() }
            .groupBy { (_, name) -> name.lowercase() }
            .values
            .filter { it.size > 1 }
            .map { group ->
                val rows = group.map { (index, _) -> index + 1 }
                val verb = if (rows.size == 2) "both" else "all"
                ValidationProblem(
                    code = "DUPLICATE_NAME",
                    message = "Rows ${joinRows(rows = rows)} are $verb named '${group.first().second}'",
                    rows = rows,
                    names = group.map { (_, name) -> name },
                )
            }

    /** One problem per pair of rows sharing a date, with the shared dates; rows with an invalid date are skipped. */
    private fun overlaps(drafts: List<ThemeScheduleDraftRow>): List<ValidationProblem> {
        val dated =
            drafts
                .mapIndexed { index, draft -> index to draft }
                .filter { (_, draft) -> ScheduleCalendar.isValid(date = draft.start) && ScheduleCalendar.isValid(date = draft.end) }
                .map { (index, draft) ->
                    Triple(first = index, second = draft, third = ScheduleCalendar.coverage(start = draft.start, end = draft.end))
                }
        return dated.flatMapIndexed { position, (index, draft, covered) ->
            dated.drop(n = position + 1).mapNotNull { (otherIndex, other, otherCovered) ->
                val shared = BooleanArray(size = ScheduleCalendar.SLOTS) { covered[it] && otherCovered[it] }
                val spans = ScheduleCalendar.spansOf(covered = shared)
                if (spans.isEmpty()) {
                    null
                } else {
                    val name = draft.name?.trim().orEmpty()
                    val otherName = other.name?.trim().orEmpty()
                    ValidationProblem(
                        code = "OVERLAP",
                        message =
                            "${label(index = index, name = name)} (${range(draft = draft)}) overlaps " +
                                "${label(
                                    index = otherIndex,
                                    name = otherName,
                                ).replaceFirstChar { it.lowercase() }} (${range(draft = other)}) " +
                                "on ${spans.joinToString(separator = ", ") { ScheduleCalendar.format(span = it) }}",
                        rows = listOf(index + 1, otherIndex + 1),
                        names = listOf(name, otherName),
                    )
                }
            }
        }
    }

    private fun range(draft: ThemeScheduleDraftRow): String =
        ScheduleCalendar.format(span = ScheduleSpan(start = draft.start, end = draft.end))

    private fun label(
        index: Int,
        name: String,
    ): String = if (name.isEmpty()) "Row ${index + 1}" else "Row ${index + 1} '$name'"

    private fun problem(
        code: String,
        message: String,
        index: Int,
        name: String,
    ): ValidationProblem =
        ValidationProblem(code = code, message = message, rows = listOf(element = index + 1), names = listOf(element = name))

    private fun joinRows(rows: List<Int>): String = rows.dropLast(n = 1).joinToString(separator = ", ") + " and " + rows.last()

    /** A row theme: any [ThemeSetting] but [ThemeSetting.AUTO], case-insensitive. */
    private fun parseTheme(raw: String): ThemeSetting? =
        ThemeSetting.entries.firstOrNull { it != ThemeSetting.AUTO && it.name.equals(other = raw, ignoreCase = true) }
}
