// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.dto.rating

import kotlinx.serialization.Serializable
import org.skopeo.common.dto.audit.AuditPersonResponse

/** Body for `POST /api/v1/rating-requests` — a player raises a re-rate request. */
@Serializable
data class CreateRatingRequestRequest(
    val justification: String,
)

/** Body for `POST /api/v1/rating-requests/{id}/approve` — the new NTRP rating to apply. */
@Serializable
data class ApproveRatingRequestRequest(
    val rating: String,
)

/** Body for `POST /api/v1/rating-requests/{id}/deny` — the required reason. */
@Serializable
data class DenyRatingRequestRequest(
    val reason: String,
)

@Serializable
data class RatingRequestResponse(
    val id: String,
    val userId: String,
    val status: String,
    val justification: String,
    // The approved rating as its published NTRP band (band only, never the raw value — privacy, #114).
    val newRating: String? = null,
    val reason: String? = null,
    val resolvedAt: String? = null,
    val createdAt: String,
    // The requester resolved to a name/code — present in the RATER list, omitted on the player's own view.
    val requester: AuditPersonResponse? = null,
)

@Serializable
data class RatingRequestPageResponse(
    val items: List<RatingRequestResponse>,
    val total: Int,
)
