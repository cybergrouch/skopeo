// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * A user's current NTRP rating, as stored. The continuous
 * [currentRating] is paired with its discrete [currentLevel] (the published level);
 * [confidence] starts low and converges with matches played.
 */
data class UserRating(
    val userId: UUID,
    val currentRating: BigDecimal,
    val currentLevel: String?,
    val confidence: BigDecimal,
    val matchesPlayed: Int,
    val lastMatchDate: LocalDate? = null,
)

/**
 * One entry in a user's rating history — a rating change driven by a match (or an
 * initial assessment, where [matchId] is null). Append-only.
 */
data class RatingHistoryEntry(
    val id: UUID,
    val userId: UUID,
    val matchId: UUID?,
    val previousRating: BigDecimal,
    val newRating: BigDecimal,
    val ratingChange: BigDecimal,
    val percentChange: BigDecimal? = null,
    val previousLevel: String? = null,
    val newLevel: String? = null,
    val levelChanged: Boolean = false,
    val dominanceFactor: BigDecimal? = null,
    val smoothingApplied: Boolean = false,
    val smoothingFactor: BigDecimal? = null,
    val calculatedAt: LocalDateTime,
)

/**
 * A user awaiting their initial rating (no rating on record) — surfaced to administrators with
 * enough context to recall and assess the player (avatar, sex, age) and a link to their profile.
 */
data class PendingAssessment(
    val userId: UUID,
    val publicCode: String,
    val displayName: String?,
    val photoUrl: String?,
    val sex: String?,
    val dateOfBirth: LocalDate?,
    val age: Int?,
)

/** A page of pending assessments plus the total count of all pending users (for pagination). */
data class PendingAssessmentPage(
    val items: List<PendingAssessment>,
    val total: Int,
)

/** A rating-history row to append when the calculation trigger commits a match. */
data class RatingHistoryWrite(
    val userId: UUID,
    val matchId: UUID,
    val previousRating: BigDecimal,
    val newRating: BigDecimal,
    val ratingChange: BigDecimal,
    val percentChange: BigDecimal?,
    val previousLevel: String?,
    val newLevel: String?,
    val levelChanged: Boolean,
    val calculatedAt: LocalDateTime,
)
