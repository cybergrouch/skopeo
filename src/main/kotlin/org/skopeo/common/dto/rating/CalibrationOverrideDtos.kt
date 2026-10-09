// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.dto.rating

import kotlinx.serialization.Serializable

/**
 * Body for `PUT /api/v1/users/{userId}/calibration-override` (#1126). [override] is AUTOMATIC, FORCED_OFF
 * or FORCED_ON; [reason] is required and non-blank, like account merge's verification note.
 */
@Serializable
data class SetCalibrationOverrideRequest(
    val override: String,
    val reason: String,
)

/**
 * A player's calibration as staff manage it (#1126): the live verdict, the progress behind it, and the
 * override with who set it, when and why. Returned by the override endpoint and listed by the Ratings
 * tab's "Players in calibration" card.
 */
@Serializable
data class PlayerCalibrationResponse(
    val userId: String,
    val publicCode: String,
    val displayName: String? = null,
    val level: String? = null,
    val inCalibration: Boolean,
    val matchesRated: Int,
    val matchesRequired: Int,
    val override: String,
    val overrideReason: String? = null,
    val overrideByName: String? = null,
    val overrideByPublicCode: String? = null,
    val overrideAt: String? = null,
)

/** A page of [PlayerCalibrationResponse] with the total, for the Ratings tab's card. */
@Serializable
data class PlayerCalibrationPageResponse(
    val items: List<PlayerCalibrationResponse>,
    val total: Int,
)
