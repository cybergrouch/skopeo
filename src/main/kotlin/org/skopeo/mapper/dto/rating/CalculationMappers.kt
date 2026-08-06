// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.dto.rating

import org.skopeo.dto.rating.CalculationBreakdownResponse
import org.skopeo.dto.rating.CalculationResponse
import org.skopeo.dto.rating.MatchCalculationDetailResponse
import org.skopeo.dto.rating.MatchCalculationResponse
import org.skopeo.dto.rating.MatchPlayerCalculationResponse
import org.skopeo.dto.rating.PlayerChangeResponse
import org.skopeo.dto.rating.SetBreakdownResponse
import org.skopeo.mapper.dto.match.toResponse
import org.skopeo.model.MatchCalculationDetail
import org.skopeo.model.MatchPlayerCalculation
import org.skopeo.model.RatingCalculationOutcome
import org.skopeo.model.RatingHistoryEntry
import org.skopeo.model.SetCalculationBreakdown

fun RatingCalculationOutcome.toResponse(): CalculationResponse =
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
