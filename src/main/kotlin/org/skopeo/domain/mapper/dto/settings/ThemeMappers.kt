// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.settings

import org.skopeo.common.dto.settings.LocalThemeResponse
import org.skopeo.common.dto.settings.ThemeResponse
import org.skopeo.domain.model.LocalThemeValue
import org.skopeo.domain.model.ThemeSettingValue

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
