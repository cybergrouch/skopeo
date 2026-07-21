// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.report

import kotlinx.serialization.Serializable

/**
 * NTRP band-hop report (#216, ADMINISTRATOR only). Over the chosen date range, each rated player's
 * band at the window start is compared with their band at the window end; the hop distance is the
 * absolute number of 0.5-wide NTRP bands moved (0 = stayed in band, the healthy majority). Only band
 * labels are exposed, never exact ratings (#64/#114).
 */
@Serializable
data class BandHopUserRow(
    val publicCode: String,
    val displayName: String?,
    val fromBand: String,
    val toBand: String,
    // True for a login-less, not-yet-claimed placeholder ("dummy") player (#496/#505): the report
    // renders an "Unclaimed" tag beside the name. Real/claimed players leave it false.
    val isPlaceholder: Boolean = false,
)

/** All players who moved a given [hopDistance] over the window, with the count for a quick summary. */
@Serializable
data class BandHopBucket(
    val hopDistance: Int,
    val count: Int,
    val users: List<BandHopUserRow>,
)

@Serializable
data class BandHopReportResponse(
    val startDate: String,
    val endDate: String,
    // Rated players considered = every player with a determinable band at both window boundaries.
    val totalPlayers: Int,
    // The headline: players whose band did not change over the window (hopDistance == 0).
    val stayedCount: Int,
    // The exceptions to inspect: players who moved at least one band (hopDistance >= 1).
    val jumpedCount: Int,
    // Every hop distance present, ascending (includes the 0 bucket).
    val buckets: List<BandHopBucket>,
)
