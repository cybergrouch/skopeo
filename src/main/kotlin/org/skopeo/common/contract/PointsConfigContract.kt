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

        /**
         * A favorite's win pays a **flat** rate rather than the margin base (#525). Ranking points are
         * band-scoped, so points earned against a materially weaker opponent would dilute the band race —
         * and a dominant scoreline over someone well below your level is not new information anyway. The
         * rate is not zero because playing the match is still effort, and it mirrors [UPSET_LOSS]: a
         * favorite gains 2 for winning and loses 2 for being upset by the same opponent, so the matchup is
         * symmetric from their side. (This is also the value the flat pre-#525 table used.)
         */
        private const val FAVORITE_WIN = 2

        /** What a losing underdog earns, by margin: a near-miss pays, a hiding does not. Kept strictly
         *  below [FAVORITE_WIN] so winning always out-earns losing in the same cell. */
        private val UNDERDOG_CONSOLATION = listOf(1, 1, 0, 0, 0, 0)

        private const val UPSET_OFFSET = 2
        private const val EQUAL_LOSS = 0
        private const val UPSET_LOSS = -2

        /**
         * The agreed dominance schedule (#525). Dominance is only rewarded where it is *informative*:
         *
         * - **Even bands** take the margin base ([MARGIN_BASE]) — a convincing win over a peer is the
         *   result the band race exists to measure.
         * - **A favorite** takes a flat [FAVORITE_WIN] whatever the scoreline; see that constant for why.
         * - **An underdog** takes the base plus [UPSET_OFFSET], because winning up is the hardest result
         *   on the table and scaling it with the margin is exactly right.
         *
         * On the losing side: even bands take [EQUAL_LOSS], a beaten underdog keeps a graduated
         * [UNDERDOG_CONSOLATION] for a competitive loss and nothing for a blowout, and a favorite who is
         * upset is docked [UPSET_LOSS]. Validity = 3 months.
         */
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
                            // Flat: the margin is deliberately ignored when the winner was the favorite.
                            winnerPoints = FAVORITE_WIN,
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

/**
 * The Full Match points window (#840). A Full Match earns the **open-play per-set amounts** — there is
 * deliberately no second amount table, so an admin editing the open-play schedule moves both surfaces at
 * once and the two can never drift. The only thing configurable here is how long those awards last:
 * longer than open play (~91 days) because a full match is a bigger occasion, shorter than a tournament
 * (365 days) because nothing in it is a season-long achievement.
 */
@Serializable
data class FullMatchPointsConfig(
    val validityDays: Int,
) {
    companion object {
        /** Six months — between the open-play and tournament windows. */
        private const val DEFAULT_VALIDITY_DAYS = 182

        val DEFAULT: FullMatchPointsConfig = FullMatchPointsConfig(validityDays = DEFAULT_VALIDITY_DAYS)
    }
}
