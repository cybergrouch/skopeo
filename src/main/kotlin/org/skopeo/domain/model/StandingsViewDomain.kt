// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.model

// The standings read-path result aggregates (#113/#220), produced by StandingsService and mapped to the
// API response by the mapper layer. Kept in model so the dto/mapper boundary never reaches into service.

/** A page of one (band, sex) group plus its selectors: the group's total, the groups on offer, all bands. */
data class StandingsView(
    val band: StandingsBand?,
    val sex: String?,
    val entries: List<StandingEntry>,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val groups: List<GroupRef>,
    // Every NTRP band (strongest-first), so the UI dropdown lists empty bands too (#113); picking an
    // empty band yields an empty page still queryable by sex. Independent of which groups have data.
    val allBands: List<StandingsBand>,
    val revealRates: Boolean,
    // The effective serving source (#428): RATING = live calculation, POINTS = the POINTS snapshot (or an
    // explicit empty view when none exists). Lets the UI distinguish "POINTS, no data yet" from ratings.
    val source: SnapshotSource,
)

/** A selectable (band, sex) group present in the leaderboard — powers the UI band dropdown + sex toggle. */
data class GroupRef(
    val band: StandingsBand,
    val sex: String?,
)

/** Jump-to-me (#220): the caller's (band, sex, rank) plus the page offset that contains their row. */
data class LocateView(
    val location: StandingsLocation,
    val offset: Int,
    val limit: Int,
)
