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
