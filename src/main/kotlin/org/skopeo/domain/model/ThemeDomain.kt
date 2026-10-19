// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.model

import kotlinx.serialization.Serializable
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
 * A yearless calendar date in the AUTO theme schedule (#1196): [month] is 1-based (Jan = 1). Not validated
 * on construction, because a request has to be able to carry an impossible date (Apr 31) far enough to be
 * reported as a row problem; `ThemeScheduleRules` is where validity is decided.
 */
@Serializable
data class ScheduleDate(
    val month: Int,
    val day: Int,
)

/**
 * One validated row of the AUTO theme schedule (#1196): [theme] (never [ThemeSetting.AUTO]) applies from
 * [start] to [end], both inclusive and repeating every year. An [end] before [start] wraps past Dec 31.
 * Rows are tested top to bottom and the first match wins; a date no row covers shows Skopeo OG. Also the
 * stored JSON shape of the `ui_theme_schedule` setting.
 */
@Serializable
data class ThemeScheduleRow(
    val name: String,
    val theme: ThemeSetting,
    val start: ScheduleDate,
    val end: ScheduleDate,
)

/**
 * An unvalidated schedule row as submitted (#1196): [name] and [theme] may be missing or wrong, which
 * `ThemeScheduleRules.validate` reports per row rather than failing the whole request.
 */
data class ThemeScheduleDraftRow(
    val name: String?,
    val theme: String?,
    val start: ScheduleDate,
    val end: ScheduleDate,
)

/** An inclusive run of yearless dates, e.g. an uncovered gap in the schedule (#1196). */
data class ScheduleSpan(
    val start: ScheduleDate,
    val end: ScheduleDate,
)

/**
 * The AUTO theme schedule (#1196) plus its provenance. [updatedBy] is null for the migration-seeded
 * default; both are null when no stored schedule exists and the built-in default is served instead.
 */
data class ThemeScheduleValue(
    val rows: List<ThemeScheduleRow>,
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

/**
 * The "Facebook login enabled" feature flag (#647) plus its provenance: who last set it ([updatedBy]) and
 * when ([updatedAt]). Both are null when the flag has never been explicitly set (default is enabled). When
 * false, the web client hides the "Continue with Facebook" sign-in/sign-up buttons — an interim kill-switch
 * while the Meta app is misconfigured.
 */
data class FacebookLoginValue(
    val enabled: Boolean,
    val updatedBy: UUID?,
    val updatedAt: LocalDateTime?,
)

/**
 * The "award ranking points" feature flag (#641) plus its provenance ([updatedBy]/[updatedAt], null until
 * set). Default is DISABLED: while false the event-create form hides the "Award Ranking Points" checkbox so
 * a host can't opt an event into awarding — preventing the accidental awards seen during the testing phase.
 * An admin turns it on when awarding should be available.
 */
data class AwardRankingPointsValue(
    val enabled: Boolean,
    val updatedBy: UUID?,
    val updatedAt: LocalDateTime?,
)

/**
 * The "hide ranking points from players" flag (#865), with its provenance.
 *
 * [hidden] is **opt-in suppression**: false — the default, and what an unseeded database means — leaves
 * points visible exactly as before, so merging the flag changes nothing until an admin ticks it.
 */
data class HideRankingPointsValue(
    val hidden: Boolean,
    val updatedBy: UUID?,
    val updatedAt: LocalDateTime?,
)

/**
 * N for the calibration window (#881), with its provenance — the number of **rated** matches a
 * manually-rated player stays in calibration for.
 *
 * Only the number lives here. Whether any given player is calibrating is derived at read time from their
 * designation timestamp, their rated-match count and this value, so changing it applies to everyone at
 * once rather than needing a sweep.
 */
data class CalibrationMatchesValue(
    val matches: Int,
    val updatedBy: UUID?,
    val updatedAt: LocalDateTime?,
)

/**
 * The stale-account threshold (#1122): how many days after sign-up an account the stale-account rule
 * matches becomes eligible for the sweep. Read at sweep time, so a change applies from the next run.
 */
data class StaleAccountDaysValue(
    val days: Int,
    val updatedBy: UUID?,
    val updatedAt: LocalDateTime?,
)
