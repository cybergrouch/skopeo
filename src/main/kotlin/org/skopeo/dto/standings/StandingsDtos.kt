// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.standings

import kotlinx.serialization.Serializable
import org.skopeo.model.BandStandings
import org.skopeo.model.StandingEntry

/**
 * One ranked player in a band's standings (#113). Order is what's revealed; [currentRating] (the
 * precise NUMERIC(10,6) value as a string) is present only for RATER/ADMINISTRATOR viewers (#186).
 */
@Serializable
data class StandingEntryResponse(
    val rank: Int,
    val userId: String,
    val displayName: String? = null,
    val publicCode: String,
    val sex: String? = null,
    val age: Int? = null,
    val currentRating: String? = null,
)

/**
 * A single (band, sex) leaderboard (#212): the band label, the group's [sex] ("Male"/"Female", or null
 * for the Unspecified group), and its ranked players. One row per (band, sex) that has players.
 */
@Serializable
data class BandStandingsResponse(
    val band: String,
    val sex: String? = null,
    val entries: List<StandingEntryResponse>,
)

fun BandStandings.toResponse(): BandStandingsResponse =
    BandStandingsResponse(band = band.label, sex = sex, entries = entries.map { it.toResponse() })

fun StandingEntry.toResponse(): StandingEntryResponse =
    StandingEntryResponse(
        rank = rank,
        userId = userId.toString(),
        displayName = displayName,
        publicCode = publicCode,
        sex = sex,
        age = age,
        currentRating = currentRating,
    )
