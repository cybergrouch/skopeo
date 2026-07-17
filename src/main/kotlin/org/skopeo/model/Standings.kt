// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * The NTRP rating bands that each run their own "Ranking Race" / standings (issue #113). The bottom
 * (`< 3.0`) and the top (`6.0+`) are lumped; 3.0–6.0 splits into the usual 0.5-wide bands. Declared
 * weakest → strongest; [of] buckets a rating into its band. [label] is the section header shown in
 * the UI, named by the band's NTRP floor (#346), e.g. "NTRP 4.0 Band Race" covers ratings 4.0–4.5.
 */
enum class StandingsBand(
    val label: String,
    // A short, stable code persisted in standings_entries.band (VARCHAR(8), #220) and accepted as the
    // `band` query parameter — the enum name (e.g. UNDER_3_0) is too long and the label is a UI header.
    val code: String,
) {
    UNDER_3_0(label = "NTRP Under 3.0 Band Race", code = "<3.0"),
    FROM_3_0(label = "NTRP 3.0 Band Race", code = "3.0"),
    FROM_3_5(label = "NTRP 3.5 Band Race", code = "3.5"),
    FROM_4_0(label = "NTRP 4.0 Band Race", code = "4.0"),
    FROM_4_5(label = "NTRP 4.5 Band Race", code = "4.5"),
    FROM_5_0(label = "NTRP 5.0 Band Race", code = "5.0"),
    FROM_5_5(label = "NTRP 5.5 Band Race", code = "5.5"),
    SIX_PLUS(label = "NTRP 6.0+ Band Race", code = "6.0+"),
    ;

    companion object {
        fun of(rating: BigDecimal): StandingsBand =
            when {
                rating < BigDecimal("3.0") -> UNDER_3_0
                rating < BigDecimal("3.5") -> FROM_3_0
                rating < BigDecimal("4.0") -> FROM_3_5
                rating < BigDecimal("4.5") -> FROM_4_0
                rating < BigDecimal("5.0") -> FROM_4_5
                rating < BigDecimal("5.5") -> FROM_5_0
                rating < BigDecimal("6.0") -> FROM_5_5
                else -> SIX_PLUS
            }

        /** The band whose persisted [code] equals [code], or null when [code] isn't a known band. */
        fun ofCode(code: String): StandingsBand? = entries.firstOrNull { it.code == code }

        /**
         * The band whose persisted [code] equals [code], for a [code] the caller knows is valid — it was
         * written by the snapshot builder, so it is always one of the enum's own codes (#220). Throws on
         * the impossible unknown-code case rather than swallowing it behind a nullable, so the read path
         * carries no unreachable null branch.
         */
        fun requireCode(code: String): StandingsBand = ofCode(code = code) ?: error(message = "unknown standings band code: $code")
    }
}

/**
 * One row of a band's standings (#113): the player's [rank] within the band. Order is what the
 * standings reveal (#64/#114); [currentRating] (the precise NUMERIC(10,6) value) is populated only
 * for RATER/ADMINISTRATOR viewers (#186) and null for everyone else.
 */
data class StandingEntry(
    val rank: Int,
    val userId: UUID,
    val displayName: String?,
    val publicCode: String,
    val sex: String?,
    val age: Int?,
    val currentRating: String? = null,
)

/**
 * The persisted standings-snapshot lifecycle status (#220). Only PUBLISHED exists today; reads serve
 * the latest PUBLISHED snapshot. DRAFT is reserved for #146's recompute→review→publish flow.
 */
enum class SnapshotStatus { PUBLISHED, DRAFT }

/**
 * What produced a standings snapshot (#146, phase 2). Snapshots are a **POINTS-only** concept: [POINTS]
 * is the ranking-points-ledger recompute persisted by [org.skopeo.service.standings.StandingsCalculationService].
 * The [RATING] source is served by **live** calculation from current ratings (no snapshot is maintained),
 * so any historical [RATING] snapshot rows are harmless dead data. The `standings_source` app-setting
 * selects which source the Standings tab serves (RATING = live, POINTS = snapshot).
 */
enum class SnapshotSource { RATING, POINTS }

/**
 * A row about to be written into a standings snapshot (#220), source-agnostic: [orderingValue] is
 * whatever the ranking sorts by (rating today, ranking points once #146 lands). [tiebreakRating] and
 * [achievedAt] carry the D8 tie-break inputs so reads never recompute them.
 */
data class StandingsEntryWrite(
    val band: StandingsBand,
    val sex: String?,
    val rank: Int,
    val userId: UUID,
    val orderingValue: BigDecimal,
    val tiebreakRating: BigDecimal?,
    val achievedAt: LocalDateTime?,
)

/** A stored snapshot entry as read back for a page (#220) — the persisted rank plus its user id. */
data class StandingsSnapshotEntry(
    val band: StandingsBand,
    val sex: String?,
    val rank: Int,
    val userId: UUID,
    val orderingValue: BigDecimal,
)

/** One page of a (band, sex) group from the latest published snapshot (#220). */
data class StandingsPage(
    val entries: List<StandingsSnapshotEntry>,
    val total: Int,
)

/** Where a user sits in the latest published snapshot (#220): their (band, sex, rank), for jump-to-me. */
data class StandingsLocation(
    val band: StandingsBand,
    val sex: String?,
    val rank: Int,
)

/**
 * A single player's competitive standing (#448) shown on their profile: their [rank] within their
 * (band, sex) group and the [points] backing it — the ordering value under the **active**
 * `standings_source` (the live rating for RATING, the snapshot points for POINTS). Null (absent) when
 * the player isn't in the current standings (unrated / no points), so the UI shows "unranked".
 */
data class PlayerStanding(
    val band: StandingsBand,
    val sex: String?,
    val rank: Int,
    val points: BigDecimal,
    // Which race this rank is for (#424): RATING = live rating, POINTS = the points snapshot.
    val source: SnapshotSource,
)
