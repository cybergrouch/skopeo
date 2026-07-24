// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.event

import org.skopeo.model.MatchSetResult
import java.util.UUID

/**
 * Computes open-play ranking points (#525, TOURNAMENTS_CIRCUITS_AND_OPEN_PLAY_POINTS.md §Part B).
 *
 * A pure function of the two teams' **entry bands** and the match's set results — no I/O — so it is
 * unit-testable in isolation. Points are scored **per set and summed**; the band **relation** (equal /
 * higher / lower) is fixed for the match, but the **category** is decided per set from that relation
 * and who won that set:
 *  - equal bands → winner 3, loser 0
 *  - unequal, the higher-banded team wins the set → winner 2, loser RLP(1) + ALP
 *  - unequal, the lower-banded team wins the set (upset) → winner 5, loser RLP(-2) + ALP
 *
 * ALP (additional loser point) = 1 when, in an unequal set, the losing side took ≥ 4 games; a set
 * decided by a tiebreak with no recorded games uses the loser's tiebreak points as their games.
 * Loser totals can be negative. The overall match winner is irrelevant — each set stands alone.
 */
internal object OpenPlayPointsCalculator {
    private const val WIN_EQUAL = 3
    private const val WIN_FAVORITE = 2
    private const val WIN_UPSET = 5
    private const val RLP_FAVORITE = 1
    private const val RLP_UPSET = -2
    private const val ALP_AWARD = 1
    private const val ALP_THRESHOLD = 4

    /** Each team's summed points for a match. */
    data class TeamPoints(
        val team1: Int,
        val team2: Int,
    )

    /**
     * @param band1 team1's entry band (e.g. "4.0"); [band2] team2's. Compared numerically.
     * @param team1Id team1's id, matched against each set's winner (team2 is inferred as the other side).
     */
    fun compute(
        band1: String,
        band2: String,
        team1Id: UUID,
        sets: List<MatchSetResult>,
    ): TeamPoints {
        val b1 = band1.toBigDecimal()
        val b2 = band2.toBigDecimal()
        val equalBands = b1 == b2
        val team1IsHigher = b1 > b2
        var team1Total = 0
        var team2Total = 0
        sets.forEach { set ->
            val team1WonSet = set.winnerTeamId == team1Id
            val higherWonSet = (team1WonSet && team1IsHigher) || (!team1WonSet && !team1IsHigher)
            val loserGames = loserGamesInSet(set = set, team1WonSet = team1WonSet)
            val (winnerDelta, loserDelta) =
                setDeltas(equalBands = equalBands, higherWonSet = higherWonSet, loserGames = loserGames)
            if (team1WonSet) {
                team1Total += winnerDelta
                team2Total += loserDelta
            } else {
                team2Total += winnerDelta
                team1Total += loserDelta
            }
        }
        return TeamPoints(team1 = team1Total, team2 = team2Total)
    }

    /** Games the set's loser took; a tiebreak-only set (0 games) uses the loser's tiebreak points. */
    private fun loserGamesInSet(
        set: MatchSetResult,
        team1WonSet: Boolean,
    ): Int {
        val games = if (team1WonSet) set.team2Games else set.team1Games
        val tiebreak = if (team1WonSet) set.tiebreakTeam2Points else set.tiebreakTeam1Points
        return if (games == 0 && tiebreak != null) tiebreak else games
    }

    /** (winner delta, loser delta) for one set given the band relation and the loser's games. */
    private fun setDeltas(
        equalBands: Boolean,
        higherWonSet: Boolean,
        loserGames: Int,
    ): Pair<Int, Int> {
        val alp = if (loserGames >= ALP_THRESHOLD) ALP_AWARD else 0
        return when {
            equalBands -> WIN_EQUAL to 0
            higherWonSet -> WIN_FAVORITE to (RLP_FAVORITE + alp)
            else -> WIN_UPSET to (RLP_UPSET + alp)
        }
    }
}
