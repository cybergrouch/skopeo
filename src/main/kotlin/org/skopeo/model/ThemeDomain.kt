// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * The global UI theme (#378). A single admin-controlled setting the web UI reads to skin itself;
 * [AUTO] lets the client pick (e.g. by season). The values are the API contract shared with the web.
 */
enum class ThemeSetting {
    AUTO,
    GRASS,
    CLAY,
    AO,
    US_OPEN,
    OFF_SEASON,
    CHRISTMAS,
}

/**
 * The resolved global theme value plus its provenance: who last set it ([updatedBy]) and when
 * ([updatedAt]). Both are null when the theme has never been explicitly set (default is [ThemeSetting.AUTO]).
 */
data class ThemeSettingValue(
    val theme: ThemeSetting,
    val updatedBy: UUID?,
    val updatedAt: LocalDateTime?,
)
