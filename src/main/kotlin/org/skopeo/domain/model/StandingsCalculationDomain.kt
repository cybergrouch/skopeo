// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.model

import java.math.BigDecimal
import java.util.UUID

// The points-based standings recompute result aggregate (#146), produced by StandingsCalculationService
// and mapped to the API response by the mapper layer. Kept in model so the dto/mapper boundary never
// reaches into the service package.

/** One ranked player within a (band, sex) group of the recompute: their points total and current rating. */
data class RankedEntry(
    val rank: Int,
    val userId: UUID,
    val displayName: String?,
    val publicCode: String,
    val points: BigDecimal,
    val currentRating: BigDecimal?,
)

/** One (band, sex) race of the recompute, ranked by points descending. */
data class GroupStanding(
    val band: StandingsBand,
    val sex: String?,
    val entries: List<RankedEntry>,
)

/** The recompute outcome: the ranked groups plus whether this was a dry run (no persist) or a commit. */
data class StandingsCalculationOutcome(
    val dryRun: Boolean,
    val groups: List<GroupStanding>,
)
