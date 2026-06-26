// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.rating

import kotlinx.serialization.Serializable
import org.skopeo.dto.match.MatchResponse
import org.skopeo.dto.match.toResponse
import org.skopeo.model.MatchCalculationDetail
import org.skopeo.model.MatchPlayerCalculation
import org.skopeo.model.RatingHistoryEntry
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

/** The internal calculator derivatives behind a player's change (issue #89). */
@Serializable
data class CalculationBreakdownResponse(
    val dominance: String,
    val scale: String,
    val ratingGap: String,
    val normalizedGap: String,
    val competitiveThresholdPct: String,
    val isUpset: Boolean,
    val upsetMultiplier: String,
    val kFactor: String,
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
 * Assemble the persisted breakdown (#97) into its response, or null when absent (initial
 * assessments and pre-#97 rows). The columns are written atomically at commit, so [kFactor]
 * present implies the rest are too; the elvis fallbacks are unreachable guards.
 */
private fun RatingHistoryEntry.toBreakdownResponse(): CalculationBreakdownResponse? =
    kFactor?.let { k ->
        CalculationBreakdownResponse(
            dominance = dominanceFactor?.toPlainString().orEmpty(),
            scale = scale?.toPlainString().orEmpty(),
            ratingGap = ratingGap?.toPlainString().orEmpty(),
            normalizedGap = normalizedGap?.toPlainString().orEmpty(),
            competitiveThresholdPct = competitiveThresholdPct?.toPlainString().orEmpty(),
            isUpset = isUpset ?: false,
            upsetMultiplier = upsetMultiplier?.toPlainString().orEmpty(),
            kFactor = k.toPlainString(),
        )
    }
