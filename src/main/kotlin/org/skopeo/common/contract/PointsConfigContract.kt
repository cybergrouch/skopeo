// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.contract

import kotlinx.serialization.Serializable

/*
 * Admin-configurable points schedules (#552/#553) — the scoped, global successor to the points policy
 * removed in V27 (#540). These types are the single source of truth for the reward-point values and
 * validity windows the finalize-time awarders use; they are stored as JSON in `points_config` and
 * edited by an ADMINISTRATOR, so the study recommendations (docs/product/POINTS_RANKING_SIMULATION_STUDY.md
 * — e.g. Seasonal validity 3mo/12mo, Fibonacci-margin increments) are reachable by editing config, not code.
 * All @Serializable so the service can JSON-encode them for storage and expose them over the API.
 */

/** Which band the set's winner was on, relative to the loser — the axis open-play points key off. */
@Serializable
enum class BandRelation {
    /** Both teams in the same band. */
    EQUAL,

    /** The higher-band team won the set (the expected result). */
    FAVORITE,

    /** The lower-band team won the set (an upset). */
    UPSET,
}

/** Winner/loser points for one (band relation × game-margin) cell of the open-play schedule (#553). */
@Serializable
data class OpenPlayMarginPoints(
    val relation: BandRelation,
    val margin: Int,
    val winnerPoints: Int,
    val loserPoints: Int,
)

/**
 * The open-play points schedule (#553): dominance by **game margin** (winner games − loser games,
 * tiebreak points count as games), indexed by band relation. The top margin ([maxMargin]) is treated
 * as ">= that margin". [validityDays] is the default award-validity window (days) when the event sets
 * no explicit one. Arbitrary per-cell values are allowed (diverse/Fibonacci increments are enterable).
 */
@Serializable
data class OpenPlayPointsConfig(
    val maxMargin: Int,
    val rows: List<OpenPlayMarginPoints>,
    val validityDays: Int,
) {
    /** The cell for [relation] at [margin] (clamped to 1..[maxMargin]); falls back to a zero cell if unset. */
    fun cell(
        relation: BandRelation,
        margin: Int,
    ): OpenPlayMarginPoints {
        val capped = margin.coerceIn(minimumValue = 1, maximumValue = maxMargin)
        return rows.firstOrNull { it.relation == relation && it.margin == capped }
            ?: OpenPlayMarginPoints(relation = relation, margin = capped, winnerPoints = 0, loserPoints = 0)
    }

    companion object {
        private const val DEFAULT_MAX_MARGIN = 6
        private const val DEFAULT_VALIDITY_DAYS = 91

        /** The winner's base for margins 1..[DEFAULT_MAX_MARGIN] — Fibonacci from 5, so dominance pays. */
        private val MARGIN_BASE = listOf(5, 8, 13, 21, 34, 55)

        /** What a losing underdog earns, by margin: a near-miss pays, a hiding does not. */
        private val UNDERDOG_CONSOLATION = listOf(2, 1, 0, 0, 0, 0)

        private const val FAVORITE_OFFSET = -1
        private const val UPSET_OFFSET = 2
        private const val EQUAL_LOSS = 0
        private const val UPSET_LOSS = -2

        /** The agreed dominance schedule (#525). The winner's **base** grows with the game margin along
         *  [MARGIN_BASE]; the band relation then adjusts it — even bands take the base, a favorite deducts
         *  [FAVORITE_OFFSET], an underdog adds [UPSET_OFFSET]. Those offsets are the same shape as the
         *  previous flat table (3 / 2 / 5 was a base of 3), so only the base changed. A losing underdog
         *  keeps a graduated consolation ([UNDERDOG_CONSOLATION]) for a competitive loss and nothing for a
         *  blowout, and the higher-rated side still loses [UPSET_LOSS] when upset. Validity = 3 months. */
        val DEFAULT: OpenPlayPointsConfig = defaultConfig()

        private fun defaultConfig(): OpenPlayPointsConfig {
            val rows =
                (1..DEFAULT_MAX_MARGIN).flatMap { margin ->
                    val base = MARGIN_BASE[margin - 1]
                    listOf(
                        OpenPlayMarginPoints(
                            relation = BandRelation.EQUAL,
                            margin = margin,
                            winnerPoints = base,
                            loserPoints = EQUAL_LOSS,
                        ),
                        OpenPlayMarginPoints(
                            relation = BandRelation.FAVORITE,
                            margin = margin,
                            winnerPoints = base + FAVORITE_OFFSET,
                            // The loser here is the underdog, so this is the consolation.
                            loserPoints = UNDERDOG_CONSOLATION[margin - 1],
                        ),
                        OpenPlayMarginPoints(
                            relation = BandRelation.UPSET,
                            margin = margin,
                            winnerPoints = base + UPSET_OFFSET,
                            // The loser here is the favorite, so this is the upset deduction.
                            loserPoints = UPSET_LOSS,
                        ),
                    )
                }
            return OpenPlayPointsConfig(maxMargin = DEFAULT_MAX_MARGIN, rows = rows, validityDays = DEFAULT_VALIDITY_DAYS)
        }
    }
}

/**
 * The tournament placement points schedule (#552): points for 1st..4th, separately for sanctioned and
 * unsanctioned tournaments, plus the default award-validity window ([validityDays]). Lists are ordered
 * 1st, 2nd, 3rd, 4th.
 */
@Serializable
data class TournamentPointsConfig(
    val sanctioned: List<Int>,
    val unsanctioned: List<Int>,
    val validityDays: Int,
) {
    /** The schedule to use given whether the tournament's club has tournaments sanctioned. */
    fun schedule(sanctioned: Boolean): List<Int> = if (sanctioned) this.sanctioned else this.unsanctioned

    companion object {
        private val DEFAULT_SANCTIONED = listOf(1000, 800, 600, 500)
        private val DEFAULT_UNSANCTIONED = listOf(400, 300, 200, 100)
        private const val DEFAULT_VALIDITY_DAYS = 365

        /** The agreed schedule (#525): sanctioned is the former table ×10 + 200 (1000/800/600/500);
         *  unsanctioned is a flat 100-point ladder (400/300/200/100). The tenfold rescale is what makes
         *  [OpenPlayPointsConfig]'s dominance scaling affordable — a title used to be worth about 1.4
         *  dominant open-play sets, which forced open-play points to stay flat; it is now worth ~18.
         *  Sanctioning therefore pays 2.5x an unsanctioned title, widening to 5x at 4th place. */
        val DEFAULT: TournamentPointsConfig =
            TournamentPointsConfig(
                sanctioned = DEFAULT_SANCTIONED,
                unsanctioned = DEFAULT_UNSANCTIONED,
                validityDays = DEFAULT_VALIDITY_DAYS,
            )
    }
}
