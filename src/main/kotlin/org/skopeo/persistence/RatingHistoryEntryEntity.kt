// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.persistence

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of a `user_rating_history` row (#633): the dumb, as-stored data with **no
 * behaviour**. Mirrors the domain `org.skopeo.model.RatingHistoryEntry` field-for-field, except
 * [setBreakdown] is held as the RAW stored JSON `String` (the per-set breakdown, #110) — the decode into
 * the domain `List<SetCalculationBreakdown>` happens at the `toDomain` boundary in the repository, since
 * `persistence` is a leaf that must not import `model`. Kept **model-free** (only stdlib types).
 */
data class RatingHistoryEntryEntity(
    val id: UUID,
    val userId: UUID,
    val matchId: UUID?,
    val previousRating: BigDecimal,
    val newRating: BigDecimal,
    val ratingChange: BigDecimal,
    val percentChange: BigDecimal?,
    val previousLevel: String?,
    val newLevel: String?,
    val levelChanged: Boolean,
    val dominanceFactor: BigDecimal?,
    val smoothingApplied: Boolean,
    val smoothingFactor: BigDecimal?,
    val scale: BigDecimal?,
    val ratingGap: BigDecimal?,
    val normalizedGap: BigDecimal?,
    val competitiveThresholdPct: BigDecimal?,
    val isUpset: Boolean?,
    val upsetMultiplier: BigDecimal?,
    val kFactor: BigDecimal?,
    val setBreakdown: String?,
    val completedAt: LocalDateTime?,
    val calculatedAt: LocalDateTime,
)
