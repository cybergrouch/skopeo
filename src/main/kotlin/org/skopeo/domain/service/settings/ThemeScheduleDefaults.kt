// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.settings

import org.skopeo.domain.model.ScheduleDate
import org.skopeo.domain.model.ThemeScheduleRow
import org.skopeo.domain.model.ThemeSetting

/** The built-in AUTO schedule (#1196); see [ThemeScheduleRules.DEFAULT]. */
internal val DEFAULT_THEME_SCHEDULE: List<ThemeScheduleRow> =
    listOf(
        row(name = "Australian Open", theme = ThemeSetting.AO, start = 1 to 1, end = 1 to 31),
        row(name = "Valentine's Day", theme = ThemeSetting.VALENTINES, start = 2 to 1, end = 2 to 14),
        row(name = "Spring", theme = ThemeSetting.SPRING, start = 2 to 15, end = 3 to 31),
        row(name = "Skopeo OG", theme = ThemeSetting.SKOPEO_OG, start = 4 to 1, end = 4 to 15),
        row(name = "Clay", theme = ThemeSetting.CLAY, start = 4 to 16, end = 6 to 10),
        row(name = "Grass", theme = ThemeSetting.GRASS, start = 6 to 11, end = 7 to 31),
        row(name = "US Open", theme = ThemeSetting.US_OPEN, start = 8 to 1, end = 9 to 15),
        row(name = "Rainy", theme = ThemeSetting.RAINY, start = 9 to 16, end = 10 to 16),
        row(name = "Off-Season", theme = ThemeSetting.OFF_SEASON, start = 10 to 17, end = 10 to 24),
        row(name = "Halloween", theme = ThemeSetting.HALLOWEEN, start = 10 to 25, end = 11 to 2),
        row(name = "Autumn", theme = ThemeSetting.AUTUMN, start = 11 to 3, end = 12 to 9),
        row(name = "Christmas", theme = ThemeSetting.CHRISTMAS, start = 12 to 10, end = 12 to 31),
    )

private fun row(
    name: String,
    theme: ThemeSetting,
    start: Pair<Int, Int>,
    end: Pair<Int, Int>,
): ThemeScheduleRow =
    ThemeScheduleRow(
        name = name,
        theme = theme,
        start = ScheduleDate(month = start.first, day = start.second),
        end = ScheduleDate(month = end.first, day = end.second),
    )
