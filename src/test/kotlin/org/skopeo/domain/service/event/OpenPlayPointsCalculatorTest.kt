// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.event

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skopeo.common.contract.BandRelation
import org.skopeo.common.contract.OpenPlayMarginPoints
import org.skopeo.common.contract.OpenPlayPointsConfig
import org.skopeo.domain.model.MatchSetResult
import java.util.UUID

/** Unit tests for the pure open-play points formula (#525/#553) — no I/O; margin-bracket + configurable. */
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
        config: OpenPlayPointsConfig = OpenPlayPointsConfig.DEFAULT,
    ) = OpenPlayPointsCalculator.compute(band1 = band1, band2 = band2, team1Id = t1, sets = sets, config = config)

    @Test
    fun `default schedule - equal bands - the winner gets the margin base, the loser 0`() {
        // 6-4 is margin 2, whose base is 8; even bands take the base unadjusted.
        val result = compute(band1 = "4.0", band2 = "4.0", sets = listOf(element = set(team1Games = 6, team2Games = 4, winner = t1)))
        result.team1 shouldBe 8
        result.team2 shouldBe 0
    }

    @Test
    fun `default schedule - a favorite deducts 1 and the underdog is consoled, an upset adds 2 and costs the favorite 2`() {
        // 6-4 is margin 2 (base 8): favorite winner 8-1=7, and the losing underdog's margin-2 consolation is 1.
        val favorite = compute(band1 = "4.5", band2 = "4.0", sets = listOf(element = set(team1Games = 6, team2Games = 4, winner = t1)))
        favorite.team1 shouldBe 7
        favorite.team2 shouldBe 1
        // 6-1 is margin 5 (base 34): upset winner 34+2=36, and the higher-rated loser is docked 2.
        val upset = compute(band1 = "3.5", band2 = "4.5", sets = listOf(element = set(team1Games = 6, team2Games = 1, winner = t1)))
        upset.team1 shouldBe 36
        upset.team2 shouldBe -2
    }

    @Test
    fun `points are summed per set`() {
        // Favorite t1 (higher): win 6-4, lose 4-6 (upset by t2), win 6-3. Default flat schedule.
        val sets =
            listOf(
                set(team1Games = 6, team2Games = 4, winner = t1),
                set(team1Games = 4, team2Games = 6, winner = t2),
                set(team1Games = 6, team2Games = 3, winner = t1),
            )
        val result = compute(band1 = "4.5", band2 = "4.0", sets = sets)
        // S1 favorite, margin 2 (base 8): t1 +7, t2 +1 (consolation).
        // S2 upset, margin 2 — lower-banded t2 wins: t2 +10, t1 −2.
        // S3 favorite, margin 3 (base 13): t1 +12, t2 +0 — margin 3 pays the underdog nothing.
        result.team1 shouldBe 7 - 2 + 12
        result.team2 shouldBe 1 + 10 + 0
    }

    @Test
    fun `margin brackets - the winner's points scale with game margin (diverse increments)`() {
        // A schedule where the favorite winner's points equal the margin (arbitrary/diverse values allowed).
        val marginScaled =
            OpenPlayPointsConfig(
                maxMargin = 6,
                rows =
                    (1..6).flatMap { m ->
                        listOf(
                            OpenPlayMarginPoints(relation = BandRelation.EQUAL, margin = m, winnerPoints = m, loserPoints = 0),
                            OpenPlayMarginPoints(relation = BandRelation.FAVORITE, margin = m, winnerPoints = m, loserPoints = 0),
                            OpenPlayMarginPoints(relation = BandRelation.UPSET, margin = m, winnerPoints = m, loserPoints = 0),
                        )
                    },
                validityDays = 90,
            )
        // 6-2 = margin 4 → winner 4.
        val margin4 =
            compute(
                band1 = "4.5",
                band2 = "4.0",
                sets = listOf(element = set(team1Games = 6, team2Games = 2, winner = t1)),
                config = marginScaled,
            )
        margin4.team1 shouldBe 4
        // 8-0 = margin 8, clamped to maxMargin 6 → winner 6.
        val clamped =
            compute(
                band1 = "4.5",
                band2 = "4.0",
                sets = listOf(element = set(team1Games = 8, team2Games = 0, winner = t1)),
                config = marginScaled,
            )
        clamped.team1 shouldBe 6
    }

    @Test
    fun `a tiebreak-only set uses tiebreak points as games for the margin`() {
        // A super-tiebreak "set": 0 games each, decided 10-8 → margin 2 (base 8). Favorite winner 7, underdog 1.
        val result =
            compute(
                band1 = "4.5",
                band2 = "4.0",
                sets = listOf(element = set(team1Games = 0, team2Games = 0, winner = t1, tb1 = 10, tb2 = 8)),
            )
        result.team1 shouldBe 7
        result.team2 shouldBe 1
    }
}
