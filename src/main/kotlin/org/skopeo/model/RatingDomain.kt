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
    // Persisted calculation breakdown (#97); all null for initial assessments and pre-#97 rows.
    // dominance is carried by [dominanceFactor]. The DTO layer assembles these into a breakdown.
    val scale: BigDecimal? = null,
    val ratingGap: BigDecimal? = null,
    val normalizedGap: BigDecimal? = null,
    val competitiveThresholdPct: BigDecimal? = null,
    val isUpset: Boolean? = null,
    val upsetMultiplier: BigDecimal? = null,
    val kFactor: BigDecimal? = null,
    val calculatedAt: LocalDateTime,
)

/**
 * The calculator derivatives behind a single rating change (#89), captured at commit time (#97) so
 * the calculation can be shown faithfully later without recomputation. [dominance] is stored in the
 * pre-existing `dominance_factor` column.
 */
data class CalculationBreakdownSnapshot(
    val dominance: BigDecimal,
    val scale: BigDecimal,
    val ratingGap: BigDecimal,
    val normalizedGap: BigDecimal,
    val competitiveThresholdPct: BigDecimal,
    val isUpset: Boolean,
    val upsetMultiplier: BigDecimal,
    val kFactor: BigDecimal,
)

/** The match result plus the stored per-player calculation behind a rated match (#97). */
data class MatchCalculationDetail(
    val match: Match,
    val players: List<MatchPlayerCalculation>,
)

/** One player's stored calculation within a rated match, with their display name for presentation. */
data class MatchPlayerCalculation(
    val userId: UUID,
    val displayName: String?,
    val history: RatingHistoryEntry,
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
    // The user's self-reported NTRP band at sign-up (issue #75), if any — admins approve or override it.
    val proposedRating: String?,
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
    // The calculation breakdown to persist alongside the change (#97).
    val breakdown: CalculationBreakdownSnapshot?,
    val calculatedAt: LocalDateTime,
)
