// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.rating

import org.skopeo.domain.model.CalibrationStatus
import org.skopeo.domain.service.settings.SettingsService
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
import java.util.UUID

/**
 * Whether a player's rating is still being **calibrated** (#881).
 *
 * A rating assigned by a human is a guess. For the first N rated matches after such a designation the
 * player's own rating moves while their opponents' and partners' do not, so a mis-assessment cannot drag
 * settled players permanently with it. This service answers only the question *"is this player in
 * calibration, and how far through?"* — the asymmetric rating effect (PR 2), the correction reversal
 * (PR 3), the points suppression (PR 4) and the band indicator (PR 5) all consume this and add nothing
 * of their own.
 *
 * **Derived, never stored.** Calibration is computed from three things every time it is asked:
 *
 * 1. `user_ratings.calibration_started_at` — stamped by every manual designation, null otherwise;
 * 2. the count of **rated** matches the player has taken part in since then;
 * 3. N, read fresh from the app-settings.
 *
 * That is a deliberate decision, not an optimisation left undone. Because N is global and mutable,
 * lowering it from 10 to 5 has to end several in-flight calibrations *at once*; a stored boolean would
 * need a sweep to do that, and would be wrong in the meantime. Raising it re-opens calibration for
 * players just past the old boundary, consistently and with no migration.
 *
 * **Prospective by construction**: `calibration_started_at` is null for every row that predates the
 * feature, so no existing manually-rated player is retroactively put into calibration. Doing otherwise
 * would silently freeze rating changes for settled players the moment this deploys.
 *
 * The single source of this rule, in the same spirit as `awardCountsInBand` (#882): the profile and the
 * standings recompute drifted apart precisely because a rule like this lived in only one of them.
 */
class CalibrationService(
    private val ratings: RatingRepository = RatingRepository(),
    private val matches: MatchRepository = MatchRepository(),
    private val settings: SettingsService = SettingsService(),
) {
    /**
     * The player's calibration state — active or not, how many rated matches they have played since the
     * designation, and the N those are measured against.
     *
     * Returns a not-calibrating status for a player who has never been manually designated, and for one
     * who has completed the window. Both are the same answer to a caller; the counts differ, which is
     * what the band indicator needs to say "match 3 of 10".
     */
    fun statusFor(userId: UUID): CalibrationStatus {
        val required = settings.getCalibrationMatches().matches
        val startedAt =
            ratings.findCurrentRating(userId = userId)?.calibrationStartedAt
                ?: return CalibrationStatus(inCalibration = false, matchesRated = 0, matchesRequired = required)
        val rated = matches.countRatedMatchesSince(userId = userId, since = startedAt)
        return CalibrationStatus(
            // Strictly less than N: the window covers the 1st through the Nth rated match, so a player
            // whose Nth match has been rated has completed it. Off by one here would either rate an
            // opponent's match that should have been suppressed, or suppress one that should have counted.
            inCalibration = rated < required,
            matchesRated = rated,
            matchesRequired = required,
        )
    }

    /** Shorthand for the common case — the rating and points paths only need the boolean. */
    fun isCalibrating(userId: UUID): Boolean = statusFor(userId = userId).inCalibration

    /**
     * Calibration state for several players in one go.
     *
     * The per-match paths need every participant's state at once (doubles: four), and N is read once for
     * the batch rather than per player — the value cannot change mid-match without making the two sides
     * of one calculation disagree.
     */
    fun statusesFor(userIds: List<UUID>): Map<UUID, CalibrationStatus> {
        val required = settings.getCalibrationMatches().matches
        val distinct = userIds.distinct()
        val startedByUser =
            ratings.findCurrentRatings(userIds = distinct).mapValues { it.value.calibrationStartedAt }
        return distinct.associateWith { userId ->
            val startedAt = startedByUser[userId]
            if (startedAt == null) {
                CalibrationStatus(inCalibration = false, matchesRated = 0, matchesRequired = required)
            } else {
                val rated = matches.countRatedMatchesSince(userId = userId, since = startedAt)
                CalibrationStatus(inCalibration = rated < required, matchesRated = rated, matchesRequired = required)
            }
        }
    }
}
