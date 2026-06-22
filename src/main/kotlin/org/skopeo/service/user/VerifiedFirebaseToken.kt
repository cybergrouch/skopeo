// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

/**
 * The trustworthy identity carried by a verified Firebase ID token, lifted out of
 * the JWT claims at the route boundary so the service layer never depends on the
 * JWT library. [providerUid] is the provider's stable subject (from the token's
 * `firebase.identities` claim), falling back to the firebase uid.
 */
data class VerifiedFirebaseToken(
    val uid: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val name: String? = null,
    val picture: String? = null,
    val signInProvider: String? = null,
    val providerUid: String,
)
