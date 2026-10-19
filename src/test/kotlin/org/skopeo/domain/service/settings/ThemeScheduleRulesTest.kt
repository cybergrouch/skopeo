// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.settings

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.skopeo.common.error.ValidationProblem
import org.skopeo.domain.model.ScheduleDate
import org.skopeo.domain.model.ScheduleSpan
import org.skopeo.domain.model.ThemeScheduleDraftRow
import org.skopeo.domain.model.ThemeScheduleRow
import org.skopeo.domain.model.ThemeSetting

/** The pure AUTO-schedule rules (#1196): overlaps, duplicate names and bad rows are errors; gaps are warnings. */
class ThemeScheduleRulesTest {
    private fun date(
        month: Int,
        day: Int,
    ) = ScheduleDate(month = month, day = day)

    private fun draft(
        name: String?,
        start: Pair<Int, Int>,
        end: Pair<Int, Int>,
        theme: String? = "CLAY",
    ) = ThemeScheduleDraftRow(
        name = name,
        theme = theme,
        start = date(month = start.first, day = start.second),
        end = date(month = end.first, day = end.second),
    )

    private fun problems(vararg drafts: ThemeScheduleDraftRow): List<ValidationProblem> =
        ThemeScheduleRules.validate(drafts = drafts.toList()).shouldBeLeft().problems

    private fun gapsOf(vararg drafts: ThemeScheduleDraftRow): List<ScheduleSpan> =
        ThemeScheduleRules.gaps(rows = ThemeScheduleRules.validate(drafts = drafts.toList()).shouldBeRight())

    private fun span(
        start: Pair<Int, Int>,
        end: Pair<Int, Int>,
    ) = ScheduleSpan(start = date(month = start.first, day = start.second), end = date(month = end.first, day = end.second))

    @Test
    fun `the default schedule is valid and leaves no gaps`() {
        val drafts =
            ThemeScheduleRules.DEFAULT.map { ThemeScheduleDraftRow(name = it.name, theme = it.theme.name, start = it.start, end = it.end) }
        val rows = ThemeScheduleRules.validate(drafts = drafts).shouldBeRight()
        rows shouldBe ThemeScheduleRules.DEFAULT
        ThemeScheduleRules.gaps(rows = rows).shouldBeEmpty()
    }

    @Test
    fun `the V68 migration seeds exactly the default schedule`() {
        val sql =
            requireNotNull(value = javaClass.classLoader.getResource("db/migration/V68__theme_schedule.sql")).readText()
        // The seeded document is the one '[ … ]' string literal; SQL doubles an embedded apostrophe.
        val literal =
            sql
                .substring(
                    startIndex = sql.indexOf(string = "'[") + 1,
                    endIndex = sql.indexOf(string = "]'") + 1,
                ).replace(oldValue = "''", newValue = "'")
        Json.decodeFromString(deserializer = ListSerializer(elementSerializer = ThemeScheduleRow.serializer()), string = literal) shouldBe
            ThemeScheduleRules.DEFAULT
    }

    @Test
    fun `a valid schedule comes back with names trimmed and themes canonical`() {
        val rows =
            ThemeScheduleRules
                .validate(
                    drafts = listOf(element = draft(name = "  Clay  ", theme = " clay ", start = 4 to 16, end = 6 to 10)),
                ).shouldBeRight()
        rows shouldContainExactly
            listOf(
                element =
                    ThemeScheduleRow(
                        name = "Clay",
                        theme = ThemeSetting.CLAY,
                        start = date(month = 4, day = 16),
                        end = date(month = 6, day = 10),
                    ),
            )
    }

    @Test
    fun `an overlap names both rows, their ranges and the shared dates`() {
        val found =
            problems(
                draft(name = "Clay", start = 4 to 16, end = 6 to 10),
                draft(name = "Grass", start = 6 to 1, end = 7 to 31, theme = "GRASS"),
            )
        found shouldHaveSize 1
        found.single().code shouldBe "OVERLAP"
        found.single().rows shouldContainExactly listOf(1, 2)
        found.single().names shouldContainExactly listOf("Clay", "Grass")
        found.single().message shouldBe "Row 1 'Clay' (Apr 16 – Jun 10) overlaps row 2 'Grass' (Jun 1 – Jul 31) on Jun 1 – Jun 10"
    }

    @Test
    fun `every overlapping pair is reported, not just the first`() {
        val found =
            problems(
                draft(name = "A", start = 1 to 1, end = 1 to 31),
                draft(name = "B", start = 1 to 15, end = 2 to 15),
                draft(name = "C", start = 1 to 20, end = 1 to 20),
            )
        found.map { it.rows } shouldContainExactly listOf(listOf(1, 2), listOf(1, 3), listOf(2, 3))
        found[1].message shouldContain "on Jan 20"
    }

    @Test
    fun `ranges crossing New Year overlap through Dec 31 and Jan 1`() {
        val found =
            problems(
                draft(name = "Holidays", start = 12 to 20, end = 1 to 5),
                draft(name = "New Year", start = 1 to 1, end = 1 to 10),
                draft(name = "Late December", start = 12 to 30, end = 12 to 31),
            )
        found.map { it.message } shouldContainExactly
            listOf(
                "Row 1 'Holidays' (Dec 20 – Jan 5) overlaps row 2 'New Year' (Jan 1 – Jan 10) on Jan 1 – Jan 5",
                "Row 1 'Holidays' (Dec 20 – Jan 5) overlaps row 3 'Late December' (Dec 30 – Dec 31) on Dec 30 – Dec 31",
            )
    }

    @Test
    fun `two wrapping ranges sharing both sides of New Year report one span across it`() {
        val found =
            problems(
                draft(name = "A", start = 12 to 1, end = 1 to 31),
                draft(name = "B", start = 12 to 25, end = 1 to 2),
            )
        found.single().message shouldContain "on Dec 25 – Jan 2"
    }

    @Test
    fun `an overlap in two separate places lists both spans`() {
        val found =
            problems(
                draft(name = "Wrap", start = 11 to 1, end = 2 to 1),
                draft(name = "Whole", start = 1 to 15, end = 11 to 15),
            )
        found.single().message shouldContain "on Jan 15 – Feb 1, Nov 1 – Nov 15"
    }

    @Test
    fun `a range ending Feb 28 covers Feb 29, so a row starting Feb 29 overlaps it`() {
        problems(
            draft(name = "February", start = 2 to 1, end = 2 to 28),
            draft(name = "Leap", start = 2 to 29, end = 3 to 31),
        ).single().message shouldContain "on Feb 29"
    }

    @Test
    fun `a range ending Feb 28 followed by Mar 1 leaves no gap`() {
        gapsOf(
            draft(name = "A", start = 1 to 1, end = 2 to 28),
            draft(name = "B", start = 3 to 1, end = 12 to 31),
        ).shouldBeEmpty()
    }

    @Test
    fun `the gap check skips Feb 29, so an uncovered Feb 28 to 29 reads as Feb 28`() {
        gapsOf(draft(name = "Most of the year", start = 3 to 1, end = 2 to 27)) shouldContainExactly
            listOf(element = span(start = 2 to 28, end = 2 to 28))
    }

    @Test
    fun `a gap spanning Feb 29 is reported around it`() {
        gapsOf(
            draft(name = "A", start = 1 to 1, end = 2 to 27),
            draft(name = "B", start = 3 to 2, end = 12 to 31),
        ) shouldContainExactly listOf(element = span(start = 2 to 28, end = 3 to 1))
    }

    @Test
    fun `gaps are listed in calendar order, with a gap across New Year kept whole`() {
        gapsOf(
            draft(name = "A", start = 1 to 10, end = 10 to 16),
            draft(name = "B", start = 10 to 25, end = 12 to 20),
        ) shouldContainExactly listOf(span(start = 10 to 17, end = 10 to 24), span(start = 12 to 21, end = 1 to 9))
    }

    @Test
    fun `an empty schedule leaves the whole year uncovered`() {
        ThemeScheduleRules.gaps(rows = emptyList()) shouldContainExactly listOf(element = span(start = 1 to 1, end = 12 to 31))
    }

    @Test
    fun `a range from Feb 29 to Feb 28 wraps to cover the whole year`() {
        gapsOf(draft(name = "Always", start = 2 to 29, end = 2 to 28)).shouldBeEmpty()
    }

    @Test
    fun `a single-day gap is reported as one date`() {
        gapsOf(
            draft(name = "A", start = 1 to 1, end = 6 to 30),
            draft(name = "B", start = 7 to 2, end = 12 to 31),
        ) shouldContainExactly listOf(element = span(start = 7 to 1, end = 7 to 1))
    }

    @Test
    fun `duplicate names clash ignoring case and surrounding spaces`() {
        val found =
            problems(
                draft(name = "Clay", start = 1 to 1, end = 1 to 31),
                draft(name = "Grass", start = 2 to 1, end = 2 to 14),
                draft(name = " clay ", start = 3 to 1, end = 3 to 31),
            )
        found.single().code shouldBe "DUPLICATE_NAME"
        found.single().rows shouldContainExactly listOf(1, 3)
        found.single().names shouldContainExactly listOf("Clay", "clay")
        found.single().message shouldBe "Rows 1 and 3 are both named 'Clay'"
    }

    @Test
    fun `three rows sharing a name are all named in one problem`() {
        problems(
            draft(name = "X", start = 1 to 1, end = 1 to 2),
            draft(name = "x", start = 2 to 1, end = 2 to 2),
            draft(name = "X ", start = 3 to 1, end = 3 to 2),
        ).single().message shouldBe "Rows 1, 2 and 3 are all named 'X'"
    }

    @Test
    fun `a missing or blank name is an error`() {
        problems(draft(name = null, start = 1 to 1, end = 1 to 2), draft(name = "   ", start = 2 to 1, end = 2 to 2))
            .map { it.code to it.message } shouldContainExactly
            listOf("MISSING_NAME" to "Row 1 has no name", "MISSING_NAME" to "Row 2 has no name")
    }

    @Test
    fun `a missing, unknown or AUTO theme is an error`() {
        problems(
            draft(name = "None", theme = null, start = 1 to 1, end = 1 to 2),
            draft(name = "Blank", theme = " ", start = 2 to 1, end = 2 to 2),
            draft(name = "Neon", theme = "NEON", start = 3 to 1, end = 3 to 2),
            draft(name = "Auto", theme = "auto", start = 4 to 1, end = 4 to 2),
        ).map { it.code to it.message } shouldContainExactly
            listOf(
                "MISSING_THEME" to "Row 1 'None' has no theme",
                "MISSING_THEME" to "Row 2 'Blank' has no theme",
                "UNKNOWN_THEME" to "Row 3 'Neon' has an unknown theme 'NEON'",
                "AUTO_THEME" to "Row 4 'Auto' can't use Auto as its theme",
            )
    }

    @Test
    fun `an impossible date is an error, and the row sits out the overlap check`() {
        val found =
            problems(
                draft(name = "April", start = 4 to 1, end = 4 to 31),
                draft(name = "Thirteenth", start = 13 to 1, end = 1 to 0),
                draft(name = "Spring", start = 4 to 10, end = 4 to 20),
            )
        found.map { it.message } shouldContainExactly
            listOf(
                "Row 1 'April' has an invalid end date (Apr 31)",
                "Row 2 'Thirteenth' has an invalid start date (month 13, day 1)",
                "Row 2 'Thirteenth' has an invalid end date (Jan 0)",
            )
        found.all { it.code == "INVALID_DATE" } shouldBe true
    }

    @Test
    fun `Feb 29 is a valid date and Feb 30 is not`() {
        ThemeScheduleRules.validate(drafts = listOf(element = draft(name = "Leap day", start = 2 to 29, end = 2 to 29))).shouldBeRight()
        problems(draft(name = "Nope", start = 2 to 30, end = 3 to 1)).single().code shouldBe "INVALID_DATE"
    }

    @Test
    fun `the error message joins every problem`() {
        val error =
            ThemeScheduleRules
                .validate(
                    drafts =
                        listOf(
                            draft(name = null, start = 1 to 1, end = 1 to 2),
                            draft(
                                name = "B",
                                theme = null,
                                start = 2 to 1,
                                end =
                                    2 to 2,
                            ),
                        ),
                ).shouldBeLeft()
        error.message shouldBe "Row 1 has no name; Row 2 'B' has no theme"
    }
}
