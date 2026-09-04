// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.event

import org.skopeo.common.contract.BandRelation
import org.skopeo.common.contract.OpenPlayPointsConfig
import org.skopeo.domain.model.MatchSetResult
import java.util.UUID

/**
 * Computes open-play ranking points (#525/#553, TOURNAMENTS_CIRCUITS_AND_OPEN_PLAY_POINTS.md §Part B).
 *
 * A pure function of the two teams' **entry bands**, the match's set results, and an admin-configurable
 * [OpenPlayPointsConfig] — no I/O — so it is unit-testable in isolation. Points are scored **per set and
 * summed**. Each set is dominance-scored by **game margin** (winner games − loser games; a tiebreak-only
 * set uses tiebreak points as games) and the band **relation** for that set:
 *  - equal bands → EQUAL
 *  - the higher-banded team won the set → FAVORITE
 *  - the lower-banded team won the set (upset) → UPSET
 *
 * The (relation, margin) cell of the config gives the winner's and loser's points. Loser totals can be
 * negative. The overall match winner is irrelevant — each set stands alone. Replaces the former binary
 * ALP (≥4 loser games) with configurable margin brackets (#553).
 */
internal object OpenPlayPointsCalculator {
    /** Each team's summed points for a match. */
    data class TeamPoints(
        val team1: Int,
        val team2: Int,
    )

    /**
     * One set's scoring, as the calculator saw it (#862).
     *
     * This exists so a derivation shown to a reader **is** the computation that paid them, not a parallel
     * reimplementation that can drift from it: [compute] is a fold over exactly these rows, so the two
     * cannot disagree about a margin, a relation, or a cell.
     */
    data class SetScoring(
        val setNumber: Int,
        val margin: Int,
        val relation: BandRelation,
        /** True when team1 won this set — which of [winnerPoints]/[loserPoints] each side received. */
        val team1WonSet: Boolean,
        val winnerPoints: Int,
        val loserPoints: Int,
    ) {
        /** What team1 earned from this set. */
        val team1Points: Int get() = if (team1WonSet) winnerPoints else loserPoints

        /** What team2 earned from this set. */
        val team2Points: Int get() = if (team1WonSet) loserPoints else winnerPoints
    }

    /**
     * Score each set, in order — the per-set detail behind a match's points (#862).
     *
     * @param band1 team1's entry band (e.g. "4.0"); [band2] team2's. Compared numerically.
     * @param team1Id team1's id, matched against each set's winner (team2 is inferred as the other side).
     * @param config the admin-configurable margin-bracket schedule.
     */
    fun scoreSets(
        band1: String,
        band2: String,
        team1Id: UUID,
        sets: List<MatchSetResult>,
        config: OpenPlayPointsConfig,
    ): List<SetScoring> {
        val b1 = band1.toBigDecimal()
        val b2 = band2.toBigDecimal()
        val equalBands = b1.compareTo(other = b2) == 0
        val team1IsHigher = b1 > b2
        return sets.mapIndexed { index, set ->
            val team1WonSet = set.winnerTeamId == team1Id
            val higherWonSet = (team1WonSet && team1IsHigher) || (!team1WonSet && !team1IsHigher)
            val relation =
                when {
                    equalBands -> BandRelation.EQUAL
                    higherWonSet -> BandRelation.FAVORITE
                    else -> BandRelation.UPSET
                }
            val margin = marginInSet(set = set, team1WonSet = team1WonSet)
            val cell = config.cell(relation = relation, margin = margin)
            SetScoring(
                setNumber = index + 1,
                margin = margin,
                relation = relation,
                team1WonSet = team1WonSet,
                winnerPoints = cell.winnerPoints,
                loserPoints = cell.loserPoints,
            )
        }
    }

    /**
     * Each team's total for the match — a fold over [scoreSets], so the totals and the explanation are the
     * same computation (#862).
     */
    fun compute(
        band1: String,
        band2: String,
        team1Id: UUID,
        sets: List<MatchSetResult>,
        config: OpenPlayPointsConfig,
    ): TeamPoints {
        val scored = scoreSets(band1 = band1, band2 = band2, team1Id = team1Id, sets = sets, config = config)
        return TeamPoints(
            team1 = scored.sumOf { it.team1Points },
            team2 = scored.sumOf { it.team2Points },
        )
    }

    /** The set's game margin (winner games − loser games); a tiebreak-only set uses tiebreak points as games. */
    private fun marginInSet(
        set: MatchSetResult,
        team1WonSet: Boolean,
    ): Int {
        val winnerGames =
            effectiveGames(
                games = if (team1WonSet) set.team1Games else set.team2Games,
                tiebreak = if (team1WonSet) set.tiebreakTeam1Points else set.tiebreakTeam2Points,
                set = set,
            )
        val loserGames =
            effectiveGames(
                games = if (team1WonSet) set.team2Games else set.team1Games,
                tiebreak = if (team1WonSet) set.tiebreakTeam2Points else set.tiebreakTeam1Points,
                set = set,
            )
        return winnerGames - loserGames
    }

    /** A side's games in a set; a tiebreak-only set (both sides 0 games) uses that side's tiebreak points. */
    private fun effectiveGames(
        games: Int,
        tiebreak: Int?,
        set: MatchSetResult,
    ): Int = if (set.team1Games == 0 && set.team2Games == 0 && tiebreak != null) tiebreak else games
}
