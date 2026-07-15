// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.math.BigDecimal
import java.util.UUID

/**
 * The NTRP rating bands that each run their own "Ranking Race" / standings (issue #113). The bottom
 * (`< 3.0`) and the top (`6.0+`) are lumped; 3.0–6.0 splits into the usual 0.5-wide bands. Declared
 * weakest → strongest; [of] buckets a rating into its band. [label] is the section header shown in
 * the UI, named by the band's NTRP floor (#346), e.g. "NTRP 4.0 Band Race" covers ratings 4.0–4.5.
 */
enum class StandingsBand(
    val label: String,
) {
    UNDER_3_0(label = "NTRP Under 3.0 Band Race"),
    FROM_3_0(label = "NTRP 3.0 Band Race"),
    FROM_3_5(label = "NTRP 3.5 Band Race"),
    FROM_4_0(label = "NTRP 4.0 Band Race"),
    FROM_4_5(label = "NTRP 4.5 Band Race"),
    FROM_5_0(label = "NTRP 5.0 Band Race"),
    FROM_5_5(label = "NTRP 5.5 Band Race"),
    SIX_PLUS(label = "NTRP 6.0+ Band Race"),
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
 * A single (band, sex) leaderboard (#212): players are ranked separately per sex within a band.
 * [sex] is the group's sex ("Male"/"Female"), or null for the "Unspecified" group (rare — sex is
 * required at sign-up). Ranks restart at 1 within each group.
 */
data class BandStandings(
    val band: StandingsBand,
    val sex: String?,
    val entries: List<StandingEntry>,
)
