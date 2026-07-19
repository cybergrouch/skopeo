// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * One set's calculator derivatives behind a player's rating change (issue #110, v2 per-set calculator).
 * All factors are precise decimal strings; [setIndex] is 0-based and [score] is "self-opponent" games.
 */
@Serializable
data class SetCalculationBreakdown(
    val setIndex: Int,
    val score: String,
    val dominance: String,
    val scale: String,
    val ratingGap: String,
    val normalizedGap: String,
    val competitiveThresholdPct: String,
    val isUpset: Boolean,
    val upsetMultiplier: String,
    val kFactor: String,
    val delta: String,
    val ratingAfter: String,
)

/**
 * A user's current NTRP rating, as stored. The continuous
 * [currentRating] is paired with its discrete [currentLevel] (the published level);
 * [confidence] starts low and converges with matches played.
 */
data class UserRating(
    val userId: UUID,
    val currentRating: BigDecimal,
    val currentLevel: String?,
    // Computed (#459), never stored: sparsity of the player's weight-class match counts over the 30-day
    // window via `confidenceAt`; 0 when there is no qualifying play in the window.
    val confidence: BigDecimal,
    val matchesPlayed: Int,
    val lastMatchDate: LocalDate? = null,
    // Timestamp of the match calc that set this rating (#343); null for self-ratings / overrides. Now
    // vestigial for confidence (#459), retained for other bookkeeping.
    val matchRatedAt: LocalDateTime? = null,
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
    // Per-set breakdown (#110); empty for v1/initial/pre-#110 rows.
    val setBreakdown: List<SetCalculationBreakdown> = emptyList(),
    // Snapshot of the source match's completed_at at commit time (#301) — the intra-batch tiebreaker
    // for ordering history newest-first. Null for match-less rows (initial assessments), which sort last.
    val completedAt: LocalDateTime? = null,
    val calculatedAt: LocalDateTime,
)

/**
 * The calculator derivatives behind a single rating change (#89), captured at commit time (#97) so
 * the calculation can be shown faithfully later without recomputation. [dominance] is stored in the
 * pre-existing `dominance_factor` column.
 */
data class CalculationBreakdownSnapshot(
    // v1 fills the net fields and leaves [sets] empty; v2 leaves the net fields null and fills [sets] (#110).
    val dominance: BigDecimal?,
    val scale: BigDecimal?,
    val ratingGap: BigDecimal?,
    val normalizedGap: BigDecimal?,
    val competitiveThresholdPct: BigDecimal?,
    val isUpset: Boolean?,
    val upsetMultiplier: BigDecimal?,
    val kFactor: BigDecimal?,
    val sets: List<SetCalculationBreakdown> = emptyList(),
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

/**
 * A rating-history row to append. [matchId] is the match that drove the change, or null for a
 * manual admin override (#96) / non-match assessment.
 */
data class RatingHistoryWrite(
    val userId: UUID,
    val matchId: UUID?,
    val previousRating: BigDecimal,
    val newRating: BigDecimal,
    val ratingChange: BigDecimal,
    val percentChange: BigDecimal?,
    val previousLevel: String?,
    val newLevel: String?,
    val levelChanged: Boolean,
    // The calculation breakdown to persist alongside the change (#97).
    val breakdown: CalculationBreakdownSnapshot?,
    // The source match's completed_at, snapshotted for ordering (#301); null for non-match rows.
    val completedAt: LocalDateTime?,
    val calculatedAt: LocalDateTime,
    // Identity of the calc batch that produced this row (#481); one id per run, shared by all its
    // rows — a deterministic ordering/grouping key. Null for admin/self-set rows (not calc batches).
    val ratingRunId: UUID?,
)

/**
 * A match-driven rating update applied to a player's current rating (#343). [ratedAt] stamps the
 * match-calc time confidence decays from; [bandJumped] marks an NTRP band change, which resets the
 * confidence ramp (matches-since-reset → 0).
 */
data class MatchRatingWrite(
    val userId: UUID,
    val newRating: BigDecimal,
    val newLevel: String?,
    val matchDate: LocalDate,
    val ratedAt: LocalDateTime,
    val bandJumped: Boolean,
)

/**
 * One participant's pre-event rating (#478), read from the `previous_rating`/`previous_level` of their
 * EARLIEST rating-history row for a match in the event being reversed. [reversal] restores their current
 * rating to exactly this. Ordering to find "earliest": `(calculated_at ASC, completed_at ASC)`, and for
 * an equal `completed_at` within a batch the row's own id ASC as a stable, deterministic tiebreak.
 */
data class PreEventRating(
    val userId: UUID,
    val previousRating: BigDecimal,
    val previousLevel: String?,
)
