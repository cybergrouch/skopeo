// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.persistence

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of a `rating_requests` row (#633): the dumb, as-stored data with **no behaviour**.
 * A flat aggregate — no child rows and no derived fields. Kept **model-free** (only stdlib types) so
 * `persistence` stays a leaf package: the lifecycle enum is stored **raw as a [String]** ([status]) and
 * only parsed into `org.skopeo.model.RatingRequestStatus` at the domain boundary (`RatingRequestEntity.toDomain`).
 * The repository maps a DB row to this, then converts it to the domain `RatingRequest` at that single point.
 */
data class RatingRequestEntity(
    val id: UUID,
    val userId: UUID,
    val justification: String,
    val status: String,
    val newRating: BigDecimal?,
    val reason: String?,
    val resolvedBy: UUID?,
    val resolvedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
)
