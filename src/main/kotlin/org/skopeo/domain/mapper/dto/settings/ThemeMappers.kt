// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.settings

import org.skopeo.common.dto.settings.LocalThemeResponse
import org.skopeo.common.dto.settings.ScheduleDateDto
import org.skopeo.common.dto.settings.ScheduleSpanDto
import org.skopeo.common.dto.settings.ThemeResponse
import org.skopeo.common.dto.settings.ThemeScheduleResponse
import org.skopeo.common.dto.settings.ThemeScheduleRowDto
import org.skopeo.common.dto.settings.ThemeScheduleRowRequest
import org.skopeo.domain.model.LocalThemeValue
import org.skopeo.domain.model.ScheduleDate
import org.skopeo.domain.model.ScheduleSpan
import org.skopeo.domain.model.ThemeScheduleDraftRow
import org.skopeo.domain.model.ThemeScheduleRow
import org.skopeo.domain.model.ThemeScheduleValue
import org.skopeo.domain.model.ThemeSettingValue

fun ThemeSettingValue.toResponse(schedule: ThemeScheduleValue): ThemeResponse =
    ThemeResponse(
        theme = theme.name,
        updatedAt = updatedAt?.toString(),
        updatedBy = updatedBy?.toString(),
        schedule = schedule.rows.map { it.toDto() },
        scheduleUpdatedAt = schedule.updatedAt?.toString(),
        scheduleUpdatedBy = schedule.updatedBy?.toString(),
    )

fun ThemeScheduleValue.toResponse(gaps: List<ScheduleSpan>): ThemeScheduleResponse =
    ThemeScheduleResponse(
        schedule = rows.map { it.toDto() },
        gaps = gaps.map { ScheduleSpanDto(start = it.start.toDto(), end = it.end.toDto()) },
        updatedAt = updatedAt?.toString(),
        updatedBy = updatedBy?.toString(),
    )

fun ThemeScheduleRowRequest.toDraft(): ThemeScheduleDraftRow =
    ThemeScheduleDraftRow(name = name, theme = theme, start = start.toDomain(), end = end.toDomain())

private fun ThemeScheduleRow.toDto(): ThemeScheduleRowDto =
    ThemeScheduleRowDto(name = name, theme = theme.name, start = start.toDto(), end = end.toDto())

private fun ScheduleDate.toDto(): ScheduleDateDto = ScheduleDateDto(month = month, day = day)

private fun ScheduleDateDto.toDomain(): ScheduleDate = ScheduleDate(month = month, day = day)

fun LocalThemeValue.toResponse(): LocalThemeResponse =
    LocalThemeResponse(
        theme = theme?.name,
        setAt = setAt?.toString(),
    )
