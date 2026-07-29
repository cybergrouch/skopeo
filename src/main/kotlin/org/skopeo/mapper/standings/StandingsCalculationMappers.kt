// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.standings

import org.skopeo.dto.standings.StandingsCalculationEntryResponse
import org.skopeo.dto.standings.StandingsCalculationGroupResponse
import org.skopeo.dto.standings.StandingsCalculationResponse
import org.skopeo.model.StandingsCalculationOutcome

fun StandingsCalculationOutcome.toResponse(): StandingsCalculationResponse =
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
