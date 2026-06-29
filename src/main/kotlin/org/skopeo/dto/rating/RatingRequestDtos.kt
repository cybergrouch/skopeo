// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.rating

import kotlinx.serialization.Serializable
import org.skopeo.dto.audit.AuditPersonResponse
import org.skopeo.model.Level
import org.skopeo.model.RatingRequest
import org.skopeo.model.RatingRequestPage
import org.skopeo.model.RatingRequestView
import java.time.ZoneOffset

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

private fun RatingRequest.toResponse(requester: AuditPersonResponse? = null): RatingRequestResponse =
    RatingRequestResponse(
        id = id.toString(),
        userId = userId.toString(),
        status = status.name,
        justification = justification,
        newRating = newRating?.let { Level.fromValue(value = it.toPlainString()).value },
        reason = reason,
        resolvedAt = resolvedAt?.toInstant(ZoneOffset.UTC)?.toString(),
        createdAt = createdAt.toInstant(ZoneOffset.UTC).toString(),
        requester = requester,
    )

/** The player's own request (no requester resolution needed). */
fun RatingRequest.toResponse(): RatingRequestResponse = toResponse(requester = null)

fun RatingRequestView.toResponse(): RatingRequestResponse =
    request.toResponse(
        requester =
            requester?.let { AuditPersonResponse(userId = it.userId.toString(), displayName = it.displayName, publicCode = it.publicCode) },
    )

fun RatingRequestPage.toResponse(): RatingRequestPageResponse =
    RatingRequestPageResponse(items = items.map { it.toResponse() }, total = total)
