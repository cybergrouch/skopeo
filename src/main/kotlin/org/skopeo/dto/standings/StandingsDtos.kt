// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.standings

import kotlinx.serialization.Serializable
import org.skopeo.model.BandStandings
import org.skopeo.model.StandingEntry

/** One ranked player in a band's standings (#113). No exact rating — order only. */
@Serializable
data class StandingEntryResponse(
    val rank: Int,
    val userId: String,
    val displayName: String? = null,
    val publicCode: String,
    val sex: String? = null,
    val age: Int? = null,
)

/** A single band's leaderboard: the band label plus its ranked players. */
@Serializable
data class BandStandingsResponse(
    val band: String,
    val entries: List<StandingEntryResponse>,
)

fun BandStandings.toResponse(): BandStandingsResponse = BandStandingsResponse(band = band.label, entries = entries.map { it.toResponse() })

fun StandingEntry.toResponse(): StandingEntryResponse =
    StandingEntryResponse(
        rank = rank,
        userId = userId.toString(),
        displayName = displayName,
        publicCode = publicCode,
        sex = sex,
        age = age,
    )
