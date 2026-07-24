// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.event

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skopeo.model.MatchSetResult
import java.util.UUID

/** Unit tests for the pure open-play points formula (#525) — no I/O. */
class OpenPlayPointsCalculatorTest {
    private val t1: UUID = UUID.randomUUID()
    private val t2: UUID = UUID.randomUUID()

    private fun set(
        team1Games: Int,
        team2Games: Int,
        winner: UUID,
        tb1: Int? = null,
        tb2: Int? = null,
    ) = MatchSetResult(
        setNumber = 1,
        team1Games = team1Games,
        team2Games = team2Games,
        winnerTeamId = winner,
        tiebreakTeam1Points = tb1,
        tiebreakTeam2Points = tb2,
    )

    private fun compute(
        band1: String,
        band2: String,
        sets: List<MatchSetResult>,
    ) = OpenPlayPointsCalculator.compute(band1 = band1, band2 = band2, team1Id = t1, sets = sets)

    @Test
    fun `equal bands - the set winner gets 3, the loser 0 regardless of games`() {
        val result = compute(band1 = "4.0", band2 = "4.0", sets = listOf(element = set(team1Games = 6, team2Games = 4, winner = t1)))
        result.team1 shouldBe 3
        result.team2 shouldBe 0
    }

    @Test
    fun `favorite wins - winner 2, loser gets RLP 1 plus ALP when they took at least 4 games`() {
        // t1 is the higher band and wins; the loser (t2) took 4 games → ALP = 1 → loser total 2.
        val withAlp = compute(band1 = "4.5", band2 = "4.0", sets = listOf(element = set(team1Games = 6, team2Games = 4, winner = t1)))
        withAlp.team1 shouldBe 2
        withAlp.team2 shouldBe 2
        // Loser took only 2 games → no ALP → loser total 1.
        val noAlp = compute(band1 = "4.5", band2 = "4.0", sets = listOf(element = set(team1Games = 6, team2Games = 2, winner = t1)))
        noAlp.team1 shouldBe 2
        noAlp.team2 shouldBe 1
    }

    @Test
    fun `upset - the lower-banded winner gets 5, the higher-banded loser goes negative`() {
        // t1 is the LOWER band and wins the set (upset). Loser t2 took 4 games → -2 + 1 = -1.
        val withAlp = compute(band1 = "3.5", band2 = "4.5", sets = listOf(element = set(team1Games = 6, team2Games = 4, winner = t1)))
        withAlp.team1 shouldBe 5
        withAlp.team2 shouldBe -1
        // Loser took only 1 game → no ALP → -2.
        val noAlp = compute(band1 = "3.5", band2 = "4.5", sets = listOf(element = set(team1Games = 6, team2Games = 1, winner = t1)))
        noAlp.team1 shouldBe 5
        noAlp.team2 shouldBe -2
    }

    @Test
    fun `a tiebreak-decided set uses the loser's tiebreak points as their games for ALP`() {
        // A super-tiebreak "set": 0 games each, decided 10-8. Favorite t1 wins; loser's 8 ≥ 4 → ALP.
        val result =
            compute(
                band1 = "4.5",
                band2 = "4.0",
                sets = listOf(element = set(team1Games = 0, team2Games = 0, winner = t1, tb1 = 10, tb2 = 8)),
            )
        result.team1 shouldBe 2
        result.team2 shouldBe 2
    }

    @Test
    fun `points are summed per set - a 3-set match can out-score the single-set table`() {
        // Favorite t1 (higher) over three sets: win 6-4, lose 4-6, win 6-3 (from the design's worked example).
        val sets =
            listOf(
                set(team1Games = 6, team2Games = 4, winner = t1),
                set(team1Games = 4, team2Games = 6, winner = t2),
                set(team1Games = 6, team2Games = 3, winner = t1),
            )
        val result = compute(band1 = "4.5", band2 = "4.0", sets = sets)
        // S1 favorite: t1 +2, t2 1+ALP(4)=2. S2 upset (lower t2 wins): t2 +5, t1 -2+ALP(4)=-1.
        // S3 favorite: t1 +2, t2 1+0=1. Totals: t1 = 2-1+2 = 3; t2 = 2+5+1 = 8.
        result.team1 shouldBe 3
        result.team2 shouldBe 8
    }
}
