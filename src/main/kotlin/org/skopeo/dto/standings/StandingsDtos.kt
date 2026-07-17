// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.standings

import kotlinx.serialization.Serializable
import org.skopeo.model.StandingEntry
import org.skopeo.service.standings.StandingsService

/**
 * One ranked player in a band's standings (#113). Order is what's revealed. The competitive metric is
 * source-aware (#457): under POINTS [points] carries the row's points total and is present for every
 * viewer (public, #64/#114); under RATING [currentRating] (the precise NUMERIC(10,6) value as a string)
 * is present only for RATER/ADMINISTRATOR viewers (#186). Only one of the two is set per served source.
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
    val points: String? = null,
)

/** A selectable (band, sex) group present in the current snapshot (#220): the band code + label + sex. */
@Serializable
data class StandingsGroupResponse(
    val band: String,
    val label: String,
    val sex: String? = null,
)

/** One NTRP band selector (#113): its persisted [code] and UI [label], listed whether or not it has data. */
@Serializable
data class StandingsBandResponse(
    val code: String,
    val label: String,
)

/**
 * One page of a (band, sex) leaderboard from the latest published snapshot (#220). [band]/[sex] is the
 * group actually served (defaulted when the request omitted a valid selector); [total] is the group's
 * full size (for paging); [groups] lists the (band, sex) selectors that actually have data, while [bands]
 * lists EVERY NTRP band (strongest-first) so the UI dropdown can offer empty bands too (#113). [band] is
 * null only for an empty snapshot (no players rated yet). [source] is the effective serving source
 * ("RATING"/"POINTS", #428) so the client can distinguish "POINTS with no snapshot yet" (empty entries)
 * from the rating leaderboard rather than silently falling back to ratings.
 */
@Serializable
data class StandingsPageResponse(
    val band: String? = null,
    val label: String? = null,
    val sex: String? = null,
    val limit: Int,
    val offset: Int,
    val total: Int,
    val entries: List<StandingEntryResponse>,
    val groups: List<StandingsGroupResponse>,
    val bands: List<StandingsBandResponse>,
    val source: String,
)

/**
 * Jump-to-me (#220): where the caller sits in the latest published snapshot — their (band, sex, rank) —
 * plus the page [offset] (with the [limit] used) that contains their row so the client can load it.
 */
@Serializable
data class StandingsLocateResponse(
    val band: String,
    val label: String,
    val sex: String? = null,
    val rank: Int,
    val limit: Int,
    val offset: Int,
)

fun StandingsService.StandingsView.toResponse(): StandingsPageResponse =
    StandingsPageResponse(
        band = band?.code,
        label = band?.label,
        sex = sex,
        limit = limit,
        offset = offset,
        total = total,
        entries = entries.map { it.toResponse() },
        groups = groups.map { StandingsGroupResponse(band = it.band.code, label = it.band.label, sex = it.sex) },
        bands = allBands.map { StandingsBandResponse(code = it.code, label = it.label) },
        source = source.name,
    )

fun StandingsService.LocateView.toResponse(): StandingsLocateResponse =
    StandingsLocateResponse(
        band = location.band.code,
        label = location.band.label,
        sex = location.sex,
        rank = location.rank,
        limit = limit,
        offset = offset,
    )

fun StandingEntry.toResponse(): StandingEntryResponse =
    StandingEntryResponse(
        rank = rank,
        userId = userId.toString(),
        displayName = displayName,
        publicCode = publicCode,
        sex = sex,
        age = age,
        currentRating = currentRating,
        points = points,
    )
