// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.dto.settings

import kotlinx.serialization.Serializable

/**
 * The "Facebook login enabled" feature flag (#647): whether the "Continue with Facebook" buttons are
 * shown, plus provenance ([updatedAt] as an ISO string, [updatedBy] as a UUID string) — both null when the
 * flag has never been explicitly set (default is enabled). Publicly readable so the unauthenticated
 * sign-in/sign-up pages can decide whether to render the Facebook buttons.
 */
@Serializable
data class FacebookLoginResponse(
    val enabled: Boolean,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
)

/** Body for `PUT /api/v1/settings/facebook-login` — enable/disable the Facebook sign-in buttons (#647). */
@Serializable
data class SetFacebookLoginRequest(
    val enabled: Boolean,
)

/**
 * The "award ranking points" feature flag (#641): whether the event-create form shows the "Award Ranking
 * Points" checkbox, plus provenance. Default is disabled (checkbox hidden) so hosts can't opt an event
 * into awarding until an admin turns it on.
 */
@Serializable
data class AwardRankingPointsResponse(
    val enabled: Boolean,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
)

/** Body for `PUT /api/v1/settings/award-ranking-points` — show/hide the award-points checkbox (#641). */
@Serializable
data class SetAwardRankingPointsRequest(
    val enabled: Boolean,
)

/**
 * `GET /api/v1/settings/hide-ranking-points` — whether ranking-point figures are hidden from players and
 * researchers (#865).
 *
 * [hidden] reads as the checkbox does: **ticked hides**. False is the default and preserves the original
 * behaviour, so the flag has no effect until an admin turns it on.
 */
@Serializable
data class HideRankingPointsResponse(
    val hidden: Boolean,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
)

/** Body for `PUT /api/v1/settings/hide-ranking-points` (#865). */
@Serializable
data class SetHideRankingPointsRequest(
    val hidden: Boolean,
)

/**
 * `GET /api/v1/settings/calibration-matches` (#881) — N, the number of rated matches a manually-rated
 * player stays in calibration for. Public read: the number is policy, not a secret, and the band
 * indicator needs it to say "match 3 of 10".
 */
@Serializable
data class CalibrationMatchesResponse(
    val matches: Int,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
)

/** Body for `PUT /api/v1/settings/calibration-matches` (#881). ADMINISTRATOR only; 1..100. */
@Serializable
data class SetCalibrationMatchesRequest(
    val matches: Int,
)
