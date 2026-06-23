// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.rating

import kotlinx.serialization.Serializable
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
)

@Serializable
data class MatchCalculationResponse(
    val matchId: String,
    val matchDate: String,
    val changes: List<PlayerChangeResponse>,
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
                            )
                        },
                )
            },
    )
