// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/** Lifecycle of a re-rate request (issue #140). */
enum class RatingRequestStatus { PENDING, APPROVED, DENIED }

/**
 * A player's rating-reconsideration request (#140). Created PENDING with a [justification]; a RATER
 * later resolves it to APPROVED (with [newRating]) or DENIED (with [reason]).
 */
data class RatingRequest(
    val id: UUID,
    val userId: UUID,
    val justification: String,
    val status: RatingRequestStatus,
    val newRating: BigDecimal? = null,
    val reason: String? = null,
    val resolvedBy: UUID? = null,
    val resolvedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime,
)

/** A re-rate request with its requester resolved to a name/code, for the RATER triage list. */
data class RatingRequestView(
    val request: RatingRequest,
    val requester: AuditPersonRef?,
)

/** A page of re-rate request views plus the total matching the filter. */
data class RatingRequestPage(
    val items: List<RatingRequestView>,
    val total: Int,
)
