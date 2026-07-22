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
    VALENTINES,
    SPRING,
    RAINY,
    HALLOWEEN,
    AUTUMN,
    SKOPEO_OG,
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

/**
 * A user's per-profile "local theme" (#514): the chosen [theme] (null = follow the global theme, the
 * default) and [setAt], the moment it was last set (null when unset). The web computes the effective
 * theme from this plus the global setting; when the global theme is AUTO, [setAt] is compared against
 * the current season's start so a new season re-applies the seasonal look.
 */
data class LocalThemeValue(
    val theme: ThemeSetting?,
    val setAt: LocalDateTime?,
)

/**
 * The resolved standings serving source (#146) plus its provenance: who last set it ([updatedBy]) and
 * when ([updatedAt]). Both are null when the source has never been explicitly set (default is
 * [SnapshotSource.RATING]).
 */
data class StandingsSourceValue(
    val source: SnapshotSource,
    val updatedBy: UUID?,
    val updatedAt: LocalDateTime?,
)
