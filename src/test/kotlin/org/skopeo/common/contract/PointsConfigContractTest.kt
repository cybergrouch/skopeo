// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.contract

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins the shipped **default** points schedules (#525) cell by cell. These values are the fallback used
 * whenever no schedule has been stored, so they are the effective production schedule on a fresh
 * environment — and because an administrator can also override them at runtime, a silent drift between
 * the agreed table and the code would otherwise surface only in live standings. Asserting the literal
 * table here makes that a test failure instead.
 */
class PointsConfigContractTest {
    @Test
    fun `the default open-play schedule matches the agreed table cell for cell`() {
        // Columns: margin, even-band winner, favorite winner, underdog winner, losing-underdog consolation.
        // The winner's base is 5 - 8 - 13 - 21 - 34 - 55; a favorite deducts 1 and an underdog adds 2.
        val expected =
            listOf(
                listOf(1, 5, 4, 7, 2),
                listOf(2, 8, 7, 10, 1),
                listOf(3, 13, 12, 15, 0),
                listOf(4, 21, 20, 23, 0),
                listOf(5, 34, 33, 36, 0),
                listOf(6, 55, 54, 57, 0),
            )
        val config = OpenPlayPointsConfig.DEFAULT
        config.maxMargin shouldBe 6
        config.validityDays shouldBe 91
        expected.forEach { row ->
            val margin = row[0]
            config.cell(relation = BandRelation.EQUAL, margin = margin).winnerPoints shouldBe row[1]
            config.cell(relation = BandRelation.FAVORITE, margin = margin).winnerPoints shouldBe row[2]
            config.cell(relation = BandRelation.UPSET, margin = margin).winnerPoints shouldBe row[3]
            // The loser under FAVORITE is the underdog, so this cell is the consolation.
            config.cell(relation = BandRelation.FAVORITE, margin = margin).loserPoints shouldBe row[4]
            config.cell(relation = BandRelation.EQUAL, margin = margin).loserPoints shouldBe 0
            // The loser under UPSET is the favorite, so this cell is the deduction.
            config.cell(relation = BandRelation.UPSET, margin = margin).loserPoints shouldBe -2
        }
    }

    @Test
    fun `winning always pays the winner more than losing pays the loser`() {
        // The consolation exists to reward a competitive loss, not to rival a win. This guards the
        // calibration that made the graduated consolation viable at all: at margin 1 a favorite wins 4
        // while the losing underdog takes 2, so the premium for actually winning is never eroded.
        val config = OpenPlayPointsConfig.DEFAULT
        (1..config.maxMargin).forEach { margin ->
            BandRelation.entries.forEach { relation ->
                val cell = config.cell(relation = relation, margin = margin)
                withClue(clue = "margin=$margin relation=$relation") {
                    cell.winnerPoints shouldBeGreaterThan cell.loserPoints
                }
            }
        }
    }

    @Test
    fun `the default tournament schedule matches the agreed table`() {
        val config = TournamentPointsConfig.DEFAULT
        // Sanctioned is the former 80/60/40/30 table scaled x10 with 200 added at every place.
        config.sanctioned shouldBe listOf(1000, 800, 600, 500)
        // Unsanctioned is a flat 100-point ladder.
        config.unsanctioned shouldBe listOf(400, 300, 200, 100)
        config.validityDays shouldBe 365
        config.schedule(sanctioned = true) shouldBe config.sanctioned
        config.schedule(sanctioned = false) shouldBe config.unsanctioned
    }

    @Test
    fun `sanctioning is worth strictly more at every placing`() {
        val config = TournamentPointsConfig.DEFAULT
        config.sanctioned.zip(other = config.unsanctioned).forEach { (sanctioned, unsanctioned) ->
            sanctioned shouldBeGreaterThan unsanctioned
        }
        // The whole commercial argument for seeking sanctioning: the gap at the title is 600 points.
        (config.sanctioned.first() - config.unsanctioned.first()) shouldBe 600
    }
}
