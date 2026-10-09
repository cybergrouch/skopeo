// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or

/** The stored override values (#1126), as written by V65 and parsed by `CalibrationService`. */
internal const val OVERRIDE_AUTOMATIC = "AUTOMATIC"
internal const val OVERRIDE_FORCED_OFF = "FORCED_OFF"
internal const val OVERRIDE_FORCED_ON = "FORCED_ON"

/**
 * "This `user_ratings` row is calibrating" in SQL — the database form of `CalibrationService`'s verdict,
 * for the places that must filter or sort on it (the Research tab, #1065, and the Ratings tab's
 * calibration list, #1126). One definition, so those two cannot disagree with each other; a test holds it
 * to `CalibrationService`, which stays the rule's home.
 *
 * - FORCED_ON: calibrating, whatever the count.
 * - FORCED_OFF: not calibrating, whatever the count.
 * - AUTOMATIC: a manual designation is on record (`calibration_started_at`) **and** fewer than [required]
 *   rated matches since. Both conditions: the count is 0-and-unread for a player never designated, and
 *   `0 < N` would otherwise match the whole population.
 *
 * [required] is the live global N, resolved once per request by `CalibrationService.requiredMatches`.
 */
internal fun calibratingRow(required: Int): Op<Boolean> =
    (UserRatingsTable.calibrationOverride eq OVERRIDE_FORCED_ON) or
        (
            (UserRatingsTable.calibrationOverride eq OVERRIDE_AUTOMATIC) and
                UserRatingsTable.calibrationStartedAt.isNotNull() and
                (UserRatingsTable.calibrationMatchesRated less required)
        )
