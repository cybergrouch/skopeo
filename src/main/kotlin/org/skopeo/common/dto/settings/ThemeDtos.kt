// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.dto.settings

import kotlinx.serialization.Serializable

/**
 * The global UI theme (#378): the [theme] name plus provenance ([updatedAt] as an ISO string,
 * [updatedBy] as a UUID string) — both null when the theme has never been explicitly set.
 *
 * [schedule] is the AUTO season schedule (#1196) every client resolves AUTO from, with its own
 * provenance in [scheduleUpdatedAt]/[scheduleUpdatedBy].
 */
@Serializable
data class ThemeResponse(
    val theme: String,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
    val schedule: List<ThemeScheduleRowDto>,
    val scheduleUpdatedAt: String? = null,
    val scheduleUpdatedBy: String? = null,
)

/** A yearless date in the AUTO schedule (#1196): [month] is 1-based. */
@Serializable
data class ScheduleDateDto(
    val month: Int,
    val day: Int,
)

/** One AUTO schedule row (#1196): [theme] applies from [start] to [end], inclusive, every year. */
@Serializable
data class ThemeScheduleRowDto(
    val name: String,
    val theme: String,
    val start: ScheduleDateDto,
    val end: ScheduleDateDto,
)

/**
 * One submitted AUTO schedule row (#1196). [name] and [theme] are nullable so a missing one is reported
 * as that row's validation problem rather than rejected as malformed JSON.
 */
@Serializable
data class ThemeScheduleRowRequest(
    val name: String? = null,
    val theme: String? = null,
    val start: ScheduleDateDto,
    val end: ScheduleDateDto,
)

/** Body for `PUT /api/v1/theme/schedule` (#1196): the whole schedule, in resolution order. */
@Serializable
data class SetThemeScheduleRequest(
    val schedule: List<ThemeScheduleRowRequest>,
)

/** An inclusive run of yearless dates (#1196). */
@Serializable
data class ScheduleSpanDto(
    val start: ScheduleDateDto,
    val end: ScheduleDateDto,
)

/**
 * The saved AUTO schedule (#1196) and its provenance. [gaps] lists the dates no row covers — a warning,
 * not an error: those dates show Skopeo OG.
 */
@Serializable
data class ThemeScheduleResponse(
    val schedule: List<ThemeScheduleRowDto>,
    val gaps: List<ScheduleSpanDto>,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
)

/** Body for `PUT /api/v1/theme` — set the global UI theme to one of the known theme names. */
@Serializable
data class SetThemeRequest(
    val theme: String,
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
