// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.entity.rating

import org.skopeo.model.RatingRequest
import org.skopeo.model.RatingRequestStatus
import org.skopeo.persistence.RatingRequestEntity

/**
 * Entity→domain mapper (#633): builds the domain [RatingRequest] from the raw persistence
 * [RatingRequestEntity] the repository returns. A flat aggregate, so this is a field-for-field copy,
 * parsing the raw [RatingRequestEntity.status] String into the [RatingRequestStatus] enum at this single
 * boundary. Lives in `mapper.entity` (which may depend on both `persistence` and `model`); the service
 * calls it, since `repository ↛ mapper`.
 */
fun RatingRequestEntity.toDomain(): RatingRequest =
    RatingRequest(
        id = id,
        userId = userId,
        justification = justification,
        status = RatingRequestStatus.valueOf(value = status),
        newRating = newRating,
        reason = reason,
        resolvedBy = resolvedBy,
        resolvedAt = resolvedAt,
        createdAt = createdAt,
    )
