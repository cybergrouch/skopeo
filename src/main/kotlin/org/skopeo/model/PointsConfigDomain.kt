// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

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
        /** Behaviour-approximating default: current flat winner (equal 3 / favorite 2 / upset 5) and loser
         *  (equal 0 / favorite 1 / upset −2) values across all margins; the game-margin dimension is present
         *  but flat, so an admin can dial in dominance/Fibonacci scaling. Open-play validity = 2 months. */
        val DEFAULT: OpenPlayPointsConfig = defaultConfig()

        private const val DEFAULT_MAX_MARGIN = 6
        private const val DEFAULT_VALIDITY_DAYS = 61
        private const val EQUAL_WIN = 3
        private const val FAVORITE_WIN = 2
        private const val UPSET_WIN = 5
        private const val EQUAL_LOSS = 0
        private const val FAVORITE_LOSS = 1
        private const val UPSET_LOSS = -2

        private fun defaultConfig(): OpenPlayPointsConfig {
            val rows =
                (1..DEFAULT_MAX_MARGIN).flatMap { margin ->
                    listOf(
                        OpenPlayMarginPoints(
                            relation = BandRelation.EQUAL,
                            margin = margin,
                            winnerPoints = EQUAL_WIN,
                            loserPoints = EQUAL_LOSS,
                        ),
                        OpenPlayMarginPoints(
                            relation = BandRelation.FAVORITE,
                            margin = margin,
                            winnerPoints = FAVORITE_WIN,
                            loserPoints = FAVORITE_LOSS,
                        ),
                        OpenPlayMarginPoints(
                            relation = BandRelation.UPSET,
                            margin = margin,
                            winnerPoints = UPSET_WIN,
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
        private val DEFAULT_SANCTIONED = listOf(80, 60, 40, 30)
        private val DEFAULT_UNSANCTIONED = listOf(40, 30, 20, 15)
        private const val DEFAULT_VALIDITY_DAYS = 365

        /** Behaviour-preserving default: sanctioned 80/60/40/30, unsanctioned 40/30/20/15, 12-month validity. */
        val DEFAULT: TournamentPointsConfig =
            TournamentPointsConfig(
                sanctioned = DEFAULT_SANCTIONED,
                unsanctioned = DEFAULT_UNSANCTIONED,
                validityDays = DEFAULT_VALIDITY_DAYS,
            )
    }
}
