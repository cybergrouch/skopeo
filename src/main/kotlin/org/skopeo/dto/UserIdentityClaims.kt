// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto

import kotlinx.serialization.Serializable

/**
 * The verified identity read from a Firebase ID token.
 *
 * Returned by the `GET /api/v1/users/me` probe while persistence is being built;
 * once the user store lands, that endpoint will return the full profile and this
 * stays the canonical "who is the caller, per the token" shape.
 */
@Serializable
data class UserIdentityClaims(
    val uid: String,
    val email: String? = null,
    val emailVerified: Boolean? = null,
    val name: String? = null,
    val picture: String? = null,
    val signInProvider: String? = null,
)
