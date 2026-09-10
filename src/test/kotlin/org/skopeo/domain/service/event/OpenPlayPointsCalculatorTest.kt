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
        abandoned: Boolean = false,
        setNumber: Int = 1,
    ) = MatchSetResult(
        setNumber = setNumber,
        team1Games = team1Games,
        team2Games = team2Games,
        winnerTeamId = winner,
        tiebreakTeam1Points = tb1,
        tiebreakTeam2Points = tb2,
        abandoned = abandoned,
    )

    private fun compute(
        band1: String,
        band2: String,
        sets: List<MatchSetResult>,
        config: OpenPlayPointsConfig = OpenPlayPointsConfig.DEFAULT,
        conceding: UUID? = null,
    ) = OpenPlayPointsCalculator.compute(
        band1 = band1,
        band2 = band2,
        sides = OpenPlayPointsCalculator.Sides(team1Id = t1, concedingTeamId = conceding),
        sets = sets,
        config = config,
    )

    @Test
    fun `default schedule - equal bands - the winner gets the margin base, the loser 0`() {
        // 6-4 is margin 2, whose base is 8; even bands take the base unadjusted.
        val result = compute(band1 = "4.0", band2 = "4.0", sets = listOf(element = set(team1Games = 6, team2Games = 4, winner = t1)))
        result.team1 shouldBe 8
        result.team2 shouldBe 0
    }

    @Test
    fun `default schedule - a favorite takes a flat rate, an upset adds 2 to the base and costs the favorite 2`() {
        // A favorite's win is flat 2 whatever the margin; the losing underdog's margin-2 consolation is 1.
        val favorite = compute(band1 = "4.5", band2 = "4.0", sets = listOf(element = set(team1Games = 6, team2Games = 4, winner = t1)))
        favorite.team1 shouldBe 2
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
        // S1 favorite (flat 2): t1 +2, t2 +1 (consolation).
        // S2 upset, margin 2 — lower-banded t2 wins: t2 +10, t1 −2.
        // S3 favorite (flat 2 again): t1 +2, t2 +0 — margin 3 pays the underdog nothing.
        result.team1 shouldBe 2 - 2 + 2
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
        // A super-tiebreak "set": 0 games each, decided 10-8 → margin 2. Favorite winner 2 (flat), underdog 1.
        val result =
            compute(
                band1 = "4.5",
                band2 = "4.0",
                sets = listOf(element = set(team1Games = 0, team2Games = 0, winner = t1, tb1 = 10, tb2 = 8)),
            )
        result.team1 shouldBe 2
        result.team2 shouldBe 1
    }

    // ---- The retirement rule (#972) -------------------------------------------------------------
    //
    // An abandoned set pays only when the designation and the games agree. All three rows of the rule
    // are pinned, because each fails differently: the first is the case the rule exists for, the second
    // is the one that must keep paying, and the third is the level set #968 will make recordable.

    @Test
    fun `an abandoned set pays nobody when the conceding side was ahead (#972)`() {
        // "5 (ret) - 1": t1 retires while dominating. Without the rule t1 collects the winner's points
        // for a match they walked out of, which is exactly what this forbids.
        val result =
            compute(
                band1 = "4.0",
                band2 = "4.0",
                sets = listOf(element = set(team1Games = 5, team2Games = 1, winner = t1, abandoned = true)),
                conceding = t1,
            )

        result.team1 shouldBe 0
        // Not the loser points either: the opponent was behind, so the set pays nobody at all.
        result.team2 shouldBe 0
    }

    @Test
    fun `an abandoned set pays the opponent on margin when they were ahead (#972)`() {
        // "1 (ret) - 5": t1 retires while losing. t2 was winning it, so t2 is paid normally — margin 4,
        // base 21 at equal bands. The retiring side still gets nothing, not even loser points.
        val result =
            compute(
                band1 = "4.0",
                band2 = "4.0",
                sets = listOf(element = set(team1Games = 1, team2Games = 5, winner = t2, abandoned = true)),
                conceding = t1,
            )

        result.team1 shouldBe 0
        result.team2 shouldBe 21
    }

    @Test
    fun `sets completed before the retirement still pay normally (#972)`() {
        // The deterrent is on the abandoned set alone. t1 won set 1 outright (6-4, margin 2, base 8),
        // then retired at 5-1 in set 2 — keeping set 1 and being paid nothing for set 2.
        val result =
            compute(
                band1 = "4.0",
                band2 = "4.0",
                sets =
                    listOf(
                        set(team1Games = 6, team2Games = 4, winner = t1, setNumber = 1),
                        set(team1Games = 5, team2Games = 1, winner = t1, abandoned = true, setNumber = 2),
                    ),
                conceding = t1,
            )

        result.team1 shouldBe 8
        result.team2 shouldBe 0
    }

    @Test
    fun `an unmarked set is unaffected by the rule, even on a match someone conceded (#972)`() {
        // The flag is what selects the set, not the presence of a conceding side. This is the guard
        // against "the last set of a retired match" reasoning: the retirement may have happened between
        // sets, leaving nothing abandoned, and a set that was played out must still pay.
        val result =
            compute(
                band1 = "4.0",
                band2 = "4.0",
                sets = listOf(element = set(team1Games = 6, team2Games = 4, winner = t1)),
                conceding = t1,
            )

        result.team1 shouldBe 8
        result.team2 shouldBe 0
    }

    @Test
    fun `an abandoned set on a normally completed match is left alone (#972)`() {
        // No conceding side means the rule cannot attribute a concession, so it declines to act rather
        // than guessing. Belt and braces: the combination should not arise, and if it does the payout
        // must not be silently misattributed.
        val result =
            compute(
                band1 = "4.0",
                band2 = "4.0",
                sets = listOf(element = set(team1Games = 6, team2Games = 4, winner = t1, abandoned = true)),
                conceding = null,
            )

        result.team1 shouldBe 8
    }
}
