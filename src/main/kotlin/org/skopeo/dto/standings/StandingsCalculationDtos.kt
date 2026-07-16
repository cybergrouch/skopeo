// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.standings

import kotlinx.serialization.Serializable
import org.skopeo.service.standings.StandingsCalculationService

/**
 * Body for `POST /api/v1/standings/calculations` (#146 phase 2). [dryRun] defaults to true (preview
 * only, no writes); set it explicitly to false to publish a points-based standings snapshot.
 */
@Serializable
data class StandingsCalculationRequest(
    val dryRun: Boolean = true,
)

/** One ranked player in a recomputed (band, sex) group: points total (string) + tie-break rating. */
@Serializable
data class StandingsCalculationEntryResponse(
    val rank: Int,
    val userId: String,
    val displayName: String? = null,
    val publicCode: String,
    val points: String,
    val currentRating: String? = null,
)

/** One recomputed (band, sex) race, ranked by points descending. */
@Serializable
data class StandingsCalculationGroupResponse(
    val band: String,
    val sex: String? = null,
    val entries: List<StandingsCalculationEntryResponse>,
)

/** The recompute outcome (#146): whether it was a dry run, the group count, and the ranked groups. */
@Serializable
data class StandingsCalculationResponse(
    val dryRun: Boolean,
    val groupsComputed: Int,
    val groups: List<StandingsCalculationGroupResponse>,
)

fun StandingsCalculationService.CalculationOutcome.toResponse(): StandingsCalculationResponse =
    StandingsCalculationResponse(
        dryRun = dryRun,
        groupsComputed = groups.size,
        groups =
            groups.map { group ->
                StandingsCalculationGroupResponse(
                    band = group.band.code,
                    sex = group.sex,
                    entries =
                        group.entries.map { entry ->
                            StandingsCalculationEntryResponse(
                                rank = entry.rank,
                                userId = entry.userId.toString(),
                                displayName = entry.displayName,
                                publicCode = entry.publicCode,
                                points = entry.points.toPlainString(),
                                currentRating = entry.currentRating?.toPlainString(),
                            )
                        },
                )
            },
    )
