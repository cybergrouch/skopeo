// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.dto.report

import kotlinx.serialization.Serializable

/**
 * NTRP band-hop report (#216/#724, ADMINISTRATOR only). Over the chosen date range each rated player's
 * band movement is reported with BOTH metrics (#724): the EXCURSION (entry band vs the FARTHEST band
 * reached in-window — a transient crossing counts, #289) and the NET (entry band vs the window's CLOSING
 * band — a round-tripper reads net 0, a stayer). Every distance is the absolute number of 0.5-wide NTRP
 * bands moved. Only band labels are exposed, never exact ratings (#64/#114).
 */
@Serializable
data class BandHopUserRow(
    val publicCode: String,
    val displayName: String?,
    // The band the player was in ENTERING the window; shared by both metrics as the "from" band.
    val fromBand: String,
    // Excursion (#289): the FARTHEST band reached in-window and its distance from the entry band.
    val excursionToBand: String,
    val excursionDistance: Int,
    // Net (#724): the band at the window CLOSE and its distance from the entry band. A player who left
    // and returned to their entry band reads netDistance 0 even when excursionDistance is positive.
    val netToBand: String,
    val netDistance: Int,
    // True for a login-less, not-yet-claimed placeholder ("dummy") player (#496/#505): the report
    // renders an "Unclaimed" tag beside the name. Real/claimed players leave it false.
    val isPlaceholder: Boolean = false,
    // True for an admin-soft-deleted account (#518): the report renders a dominant "Deleted" chip.
    val isDeleted: Boolean = false,
)

/**
 * All players who moved a given [hopDistance] under one bucketing (excursion or net), with the count for
 * a quick summary. The user rows always carry both metrics regardless of which bucketing they appear in.
 */
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
    // Rated players considered = every player with a determinable band entering the window.
    val totalPlayers: Int,
    // Excursion bucketing (#289): players by farthest-band distance from their entry band.
    // Stayed = excursionDistance 0; jumped = excursionDistance >= 1.
    val excursionStayedCount: Int,
    val excursionJumpedCount: Int,
    // Every excursion distance present, ascending (includes the 0 bucket).
    val excursionBuckets: List<BandHopBucket>,
    // Net bucketing (#724): players by closing-band distance from their entry band. A round-tripper is a
    // net stayer. Stayed = netDistance 0; jumped = netDistance >= 1.
    val netStayedCount: Int,
    val netJumpedCount: Int,
    // Every net distance present, ascending (includes the 0 bucket).
    val netBuckets: List<BandHopBucket>,
)
