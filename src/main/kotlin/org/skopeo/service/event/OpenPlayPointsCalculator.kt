// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.event

import org.skopeo.model.BandRelation
import org.skopeo.model.MatchSetResult
import org.skopeo.model.OpenPlayPointsConfig
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
     * @param band1 team1's entry band (e.g. "4.0"); [band2] team2's. Compared numerically.
     * @param team1Id team1's id, matched against each set's winner (team2 is inferred as the other side).
     * @param config the admin-configurable margin-bracket schedule.
     */
    fun compute(
        band1: String,
        band2: String,
        team1Id: UUID,
        sets: List<MatchSetResult>,
        config: OpenPlayPointsConfig,
    ): TeamPoints {
        val b1 = band1.toBigDecimal()
        val b2 = band2.toBigDecimal()
        val equalBands = b1.compareTo(other = b2) == 0
        val team1IsHigher = b1 > b2
        var team1Total = 0
        var team2Total = 0
        sets.forEach { set ->
            val team1WonSet = set.winnerTeamId == team1Id
            val higherWonSet = (team1WonSet && team1IsHigher) || (!team1WonSet && !team1IsHigher)
            val relation =
                when {
                    equalBands -> BandRelation.EQUAL
                    higherWonSet -> BandRelation.FAVORITE
                    else -> BandRelation.UPSET
                }
            val cell = config.cell(relation = relation, margin = marginInSet(set = set, team1WonSet = team1WonSet))
            if (team1WonSet) {
                team1Total += cell.winnerPoints
                team2Total += cell.loserPoints
            } else {
                team2Total += cell.winnerPoints
                team1Total += cell.loserPoints
            }
        }
        return TeamPoints(team1 = team1Total, team2 = team2Total)
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
