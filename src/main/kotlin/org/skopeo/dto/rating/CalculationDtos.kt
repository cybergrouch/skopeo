// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.rating

import kotlinx.serialization.Serializable
import org.skopeo.dto.match.MatchResponse
import org.skopeo.dto.match.toResponse
import org.skopeo.model.MatchCalculationDetail
import org.skopeo.model.MatchPlayerCalculation
import org.skopeo.model.RatingHistoryEntry
import org.skopeo.model.SetCalculationBreakdown
import org.skopeo.service.rating.RatingCalculationService

/**
 * Body for `POST /api/v1/ratings/calculations`. [dryRun] defaults to true (preview only);
 * set it explicitly to false to commit the rating changes.
 */
@Serializable
data class CalculationRequest(
    val dryRun: Boolean = true,
)

@Serializable
data class PlayerChangeResponse(
    val userId: String,
    val previousRating: String,
    val newRating: String,
    val change: String,
    val percentChange: String,
    val previousLevel: String? = null,
    val newLevel: String? = null,
    val levelChanged: Boolean,
    val breakdown: CalculationBreakdownResponse,
)

/**
 * The internal calculator derivatives behind a player's change (issue #89). The net fields are present
 * for the v1 (averaged) calculator and null for v2, which reports the per-set steps in [sets] (#110).
 */
@Serializable
data class CalculationBreakdownResponse(
    val dominance: String? = null,
    val scale: String? = null,
    val ratingGap: String? = null,
    val normalizedGap: String? = null,
    val competitiveThresholdPct: String? = null,
    val isUpset: Boolean? = null,
    val upsetMultiplier: String? = null,
    val kFactor: String? = null,
    val sets: List<SetBreakdownResponse> = emptyList(),
)

/** One set's calculator derivatives behind a player's change (v2 per-set calculator, issue #110). */
@Serializable
data class SetBreakdownResponse(
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

@Serializable
data class MatchCalculationResponse(
    val matchId: String,
    val matchDate: String,
    val changes: List<PlayerChangeResponse>,
)

/** The detail behind a rating-history entry (#97): the match result plus the stored calculation. */
@Serializable
data class MatchCalculationDetailResponse(
    val match: MatchResponse,
    val changes: List<MatchPlayerCalculationResponse>,
)

/** One player's stored calculation for a rated match, with their display name. */
@Serializable
data class MatchPlayerCalculationResponse(
    val userId: String,
    val displayName: String? = null,
    val previousRating: String,
    val newRating: String,
    val change: String,
    val percentChange: String? = null,
    val previousLevel: String? = null,
    val newLevel: String? = null,
    val levelChanged: Boolean,
    // Absent for rows that predate the persisted breakdown (#97) or initial assessments.
    val breakdown: CalculationBreakdownResponse? = null,
)

@Serializable
data class CalculationResponse(
    val dryRun: Boolean,
    val matchesProcessed: Int,
    val matches: List<MatchCalculationResponse>,
)

fun RatingCalculationService.CalculationOutcome.toResponse(): CalculationResponse =
    CalculationResponse(
        dryRun = dryRun,
        matchesProcessed = matches.size,
        matches =
            matches.map { calc ->
                MatchCalculationResponse(
                    matchId = calc.matchId.toString(),
                    matchDate = calc.matchDate.toString(),
                    changes =
                        calc.changes.map {
                            PlayerChangeResponse(
                                userId = it.userId.toString(),
                                previousRating = it.previousRating.toPlainString(),
                                newRating = it.newRating.toPlainString(),
                                change = it.change.toPlainString(),
                                percentChange = it.percentChange.toPlainString(),
                                previousLevel = it.previousLevel,
                                newLevel = it.newLevel,
                                levelChanged = it.levelChanged,
                                breakdown =
                                    CalculationBreakdownResponse(
                                        dominance = it.breakdown.dominance,
                                        scale = it.breakdown.scale,
                                        ratingGap = it.breakdown.ratingGap,
                                        normalizedGap = it.breakdown.normalizedGap,
                                        competitiveThresholdPct = it.breakdown.competitiveThresholdPct,
                                        isUpset = it.breakdown.isUpset,
                                        upsetMultiplier = it.breakdown.upsetMultiplier,
                                        kFactor = it.breakdown.kFactor,
                                        sets = it.breakdown.sets.map { set -> set.toResponse() },
                                    ),
                            )
                        },
                )
            },
    )

fun MatchCalculationDetail.toResponse(): MatchCalculationDetailResponse =
    MatchCalculationDetailResponse(
        match = match.toResponse(),
        changes = players.map { it.toResponse() },
    )

private fun MatchPlayerCalculation.toResponse(): MatchPlayerCalculationResponse =
    MatchPlayerCalculationResponse(
        userId = userId.toString(),
        displayName = displayName,
        previousRating = history.previousRating.toPlainString(),
        newRating = history.newRating.toPlainString(),
        change = history.ratingChange.toPlainString(),
        percentChange = history.percentChange?.toPlainString(),
        previousLevel = history.previousLevel,
        newLevel = history.newLevel,
        levelChanged = history.levelChanged,
        breakdown = history.toBreakdownResponse(),
    )

/**
 * Assemble the persisted breakdown (#97/#110) into its response, or null when absent (initial
 * assessments and pre-#97 rows). v1 rows carry the net fields (keyed by [kFactor] presence); v2 rows
 * carry the per-set steps in [setBreakdown] with the net fields null (#110).
 */
private fun RatingHistoryEntry.toBreakdownResponse(): CalculationBreakdownResponse? =
    when {
        kFactor != null ->
            CalculationBreakdownResponse(
                dominance = dominanceFactor?.toPlainString(),
                scale = scale?.toPlainString(),
                ratingGap = ratingGap?.toPlainString(),
                normalizedGap = normalizedGap?.toPlainString(),
                competitiveThresholdPct = competitiveThresholdPct?.toPlainString(),
                isUpset = isUpset,
                upsetMultiplier = upsetMultiplier?.toPlainString(),
                kFactor = kFactor.toPlainString(),
                sets = setBreakdown.map { it.toResponse() },
            )
        setBreakdown.isNotEmpty() -> CalculationBreakdownResponse(sets = setBreakdown.map { it.toResponse() })
        else -> null
    }

/** Map a persisted per-set breakdown (#110) to its response. */
internal fun SetCalculationBreakdown.toResponse(): SetBreakdownResponse =
    SetBreakdownResponse(
        setIndex = setIndex,
        score = score,
        dominance = dominance,
        scale = scale,
        ratingGap = ratingGap,
        normalizedGap = normalizedGap,
        competitiveThresholdPct = competitiveThresholdPct,
        isUpset = isUpset,
        upsetMultiplier = upsetMultiplier,
        kFactor = kFactor,
        delta = delta,
        ratingAfter = ratingAfter,
    )
