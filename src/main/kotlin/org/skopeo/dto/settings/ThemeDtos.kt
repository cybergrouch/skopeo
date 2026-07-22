// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.settings

import kotlinx.serialization.Serializable
import org.skopeo.model.LocalThemeValue
import org.skopeo.model.ThemeSettingValue

/**
 * The global UI theme (#378): the [theme] name plus provenance ([updatedAt] as an ISO string,
 * [updatedBy] as a UUID string) — both null when the theme has never been explicitly set.
 */
@Serializable
data class ThemeResponse(
    val theme: String,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
)

/** Body for `PUT /api/v1/theme` — set the global UI theme to one of the known theme names. */
@Serializable
data class SetThemeRequest(
    val theme: String,
)

fun ThemeSettingValue.toResponse(): ThemeResponse =
    ThemeResponse(
        theme = theme.name,
        updatedAt = updatedAt?.toString(),
        updatedBy = updatedBy?.toString(),
    )

/**
 * The caller's own local theme (#514): the chosen [theme] name and [setAt] (ISO string). Both null
 * when the user follows the global theme (the default).
 */
@Serializable
data class LocalThemeResponse(
    val theme: String? = null,
    val setAt: String? = null,
)

/**
 * Body for `PUT /api/v1/users/me/theme` (#514): set the caller's local theme to a known theme name,
 * or `{ "theme": null }` to clear it (follow the global theme).
 */
@Serializable
data class SetLocalThemeRequest(
    val theme: String? = null,
)

fun LocalThemeValue.toResponse(): LocalThemeResponse =
    LocalThemeResponse(
        theme = theme?.name,
        setAt = setAt?.toString(),
    )
