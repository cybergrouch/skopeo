// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.settings

import org.skopeo.domain.model.LocalThemeValue
import org.skopeo.domain.model.ThemeSettingValue
import org.skopeo.dto.settings.LocalThemeResponse
import org.skopeo.dto.settings.ThemeResponse

fun ThemeSettingValue.toResponse(): ThemeResponse =
    ThemeResponse(
        theme = theme.name,
        updatedAt = updatedAt?.toString(),
        updatedBy = updatedBy?.toString(),
    )

fun LocalThemeValue.toResponse(): LocalThemeResponse =
    LocalThemeResponse(
        theme = theme?.name,
        setAt = setAt?.toString(),
    )
