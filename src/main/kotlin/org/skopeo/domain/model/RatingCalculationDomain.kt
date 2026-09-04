// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.model

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

// The rating-calculation trigger's result aggregate (#89), produced by the service and mapped to the
// API response by the mapper layer. Kept in model (not the service) so the dto/mapper boundary never
// reaches into the service package.

/**
 * The internal calculator derivatives behind one player's change (issue #89), as precise strings.
 * v1 fills the net fields and leaves [sets] empty; v2 leaves the net fields null and fills [sets] (#110).
 */
data class CalculationBreakdown(
    val dominance: String?,
    val scale: String?,
    val ratingGap: String?,
    val normalizedGap: String?,
    val competitiveThresholdPct: String?,
    val isUpset: Boolean?,
    val upsetMultiplier: String?,
    val kFactor: String?,
    val sets: List<SetCalculationBreakdown> = emptyList(),
)

/** One player's computed change within a processed match. */
data class PlayerChange(
    val userId: UUID,
    val previousRating: BigDecimal,
    val newRating: BigDecimal,
    val change: BigDecimal,
    val percentChange: BigDecimal,
    val previousLevel: String?,
    val newLevel: String?,
    val levelChanged: Boolean,
    val breakdown: CalculationBreakdown,
    /**
     * True when this player's rating was **not applied** because they were not in calibration while
     * someone else in the match was (#881).
     *
     * The reported figures are then truthful rather than counterfactual: [newRating] equals
     * [previousRating], [change] is zero, and [levelChanged] is false — the rating genuinely did not
     * move. What *would* have happened is still recoverable from [breakdown], which is left intact, so
     * nothing is lost for debugging a suppression that looks wrong.
     *
     * A suppressed change writes **no** rating row and **no** history row. That absence is load-bearing:
     * `MatchScoreCorrectionService` reverses exactly what was applied, so a history row here would make a
     * later correction reverse a change that never happened.
     */
    val suppressed: Boolean = false,
)

data class MatchCalculation(
    val matchId: UUID,
    val matchDate: LocalDate,
    // The match's result-upload time — snapshotted onto each history row as the ordering
    // tiebreaker (#301). Non-null in practice (only COMPLETED matches are processed).
    val completedAt: LocalDateTime?,
    val changes: List<PlayerChange>,
)

/** The rating-calculation run outcome: the processed matches plus whether this was a dry run or a commit. */
data class RatingCalculationOutcome(
    val dryRun: Boolean,
    val matches: List<MatchCalculation>,
)
