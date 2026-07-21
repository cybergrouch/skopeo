// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.user

import kotlinx.serialization.Serializable
import org.skopeo.model.GeneratedClaimCode

// Request/response shapes for placeholder ("dummy") player accounts + claim (#496). The caller's identity
// is taken from the verified Firebase token; these bodies carry only the details the client supplies.

/**
 * Body for `POST /api/v1/users/placeholders` — create a login-less placeholder player (#496).
 * [displayName] and [sex] (Male/Female) are required; [dateOfBirth] (ISO yyyy-MM-dd) is optional.
 * [initialRating] (#503) is an optional NTRP value (1.0–7.0) set in the same flow; it is
 * RATER/ADMINISTRATOR-gated and validated only when present — a rating-less create always succeeds.
 */
@Serializable
data class CreatePlaceholderRequest(
    val displayName: String,
    val sex: String,
    val dateOfBirth: String? = null,
    val initialRating: String? = null,
)

/** Body for `POST /api/v1/users/claim` — the secret code the caller pastes to adopt a placeholder (#496). */
@Serializable
data class ClaimRequest(
    val code: String,
)

/**
 * Response for `POST /api/v1/users/{id}/claim-code` (#496): the plaintext claim code, shown once and
 * never stored/re-derivable, plus its expiry so the UI can display the deadline. The admin hands the
 * [code] to the verified person out-of-band.
 */
@Serializable
data class ClaimCodeResponse(
    val code: String,
    val expiresAt: String,
    val placeholderPublicCode: String,
)

fun GeneratedClaimCode.toResponse(): ClaimCodeResponse =
    ClaimCodeResponse(
        code = plaintext,
        expiresAt = code.expiresAt.toString(),
        placeholderPublicCode = placeholderPublicCode,
    )
