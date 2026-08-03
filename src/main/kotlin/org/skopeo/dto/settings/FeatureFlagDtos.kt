// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.settings

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
