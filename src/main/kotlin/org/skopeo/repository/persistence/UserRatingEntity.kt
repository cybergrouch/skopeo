// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of a `user_ratings` row (#633): the dumb, as-stored data with **no derived
 * fields**. Contrast the domain `org.skopeo.domain.model.UserRating`, which additionally carries the *computed*
 * `confidence` (#459) — a recency × sparsity × spacing score over the player's windowed match rows, never
 * stored. This is the direct analogue of `UserEntity`'s derived `photoUrl`: the raw row is captured here,
 * and `confidence` is computed at the single `UserRatingEntity.toDomain(windowed, now)` boundary in the
 * repository. Kept **model-free** (only stdlib types) so `persistence` stays a leaf package.
 */
data class UserRatingEntity(
    val userId: UUID,
    val currentRating: BigDecimal,
    val currentLevel: String?,
    val matchesPlayed: Int,
    val lastMatchDate: LocalDate?,
    val matchRatedAt: LocalDateTime?,
    /** When the current calibration window opened (#881); null if never manually designated. */
    val calibrationStartedAt: LocalDateTime? = null,
)
