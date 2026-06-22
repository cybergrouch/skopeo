// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.calculator.impl.v1

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skopeo.model.RatingSystem
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Exhaustive NTRP matchup matrix: every 0.5-step level pairing (1.0–7.0, 13 levels,
 * 169 ordered matchups) crossed with every legal single-set score — 1183 cells.
 * P1 (the row) is always the match winner, so cells below the diagonal are upsets
 * and cells above it are favorite wins.
 *
 * Every cell is verified against the master formula computed from first principles
 * (change = K × dominance × scale × sign, then boundary clamping at 1.0/7.0), so this
 * doubles as a property test of the calculator across the entire input space.
 *
 * The matrices (one per set score, cell = P1's delta, '*' = boundary clamping broke
 * zero-sum) are printed and written to /tmp/ntrp_matchup_matrix.txt.
 */
class NtrpMatchupMatrixReport {
    private val calculator = PerformanceBasedRankingCalculatorImpl()

    companion object {
        private const val K_NTRP = 0.16
        private const val THRESHOLD_PCT = 0.083
        private const val UPSET_MULTIPLIER = 2.0
        private const val NTRP_MIN = 1.0
        private const val NTRP_MAX = 7.0
        private const val NTRP_RANGE = NTRP_MAX - NTRP_MIN
        private const val TOLERANCE = 1e-4
        private const val ZERO_SUM_EPSILON = 1e-9
    }

    @Test
    fun generateNtrpMatchupMatrix() {
        val output =
            buildString {
                appendLine("NTRP MATCHUP MATRIX")
                appendLine("P1 (row) is always the winner; columns are P2's rating; cell = P1's rating delta.")
                appendLine("Below the diagonal P1 is the underdog (upset); above it P1 is the favorite.")
                appendLine("'*' = boundary clamping at 1.0/7.0 applied (zero-sum intentionally broken).")
                appendLine("Constants: K=$K_NTRP, threshold=$THRESHOLD_PCT, upset multiplier=$UPSET_MULTIPLIER, range=$NTRP_RANGE")
                TestScenarios.allSingleSetScores.forEach { (gamesWon, gamesLost) ->
                    append(matrixForScore(gamesWon = gamesWon, gamesLost = gamesLost))
                }
            }

        println(output)
        File("/tmp/ntrp_matchup_matrix.txt").writeText(text = output)
        println("\nResults written to /tmp/ntrp_matchup_matrix.txt")
    }

    private fun matrixForScore(
        gamesWon: Int,
        gamesLost: Int,
    ): String =
        buildString {
            val levels = TestScenarios.allNtrpLevels
            val dominance = (gamesWon - gamesLost).toDouble() / (gamesWon + gamesLost)
            appendLine()
            appendLine("=== Score $gamesWon-$gamesLost (dominance ${String.format(Locale.US, "%.3f", dominance)}) ===")
            appendLine("P1\\P2 " + levels.joinToString(separator = "") { level -> String.format(Locale.US, "%9s", level) })
            levels.forEach { p1 ->
                val cells =
                    levels.joinToString(separator = "") { p2 ->
                        cell(p1 = p1, p2 = p2, gamesWon = gamesWon, gamesLost = gamesLost)
                    }
                appendLine(String.format(Locale.US, "%-6s", p1) + cells)
            }
        }

    /**
     * Calculate one matchup through the real calculator, verify both players' deltas
     * against the independently computed expectation, and format P1's delta as a cell.
     */
    private fun cell(
        p1: String,
        p2: String,
        gamesWon: Int,
        gamesLost: Int,
    ): String {
        val result =
            calculator.calculate(
                request =
                    createSinglesRequest(
                        p1Rating = p1,
                        p2Rating = p2,
                        system = RatingSystem.NTRP,
                        p1Games = gamesWon,
                        p2Games = gamesLost,
                        winner = "T1",
                    ),
            )
        val p1Delta = result.response.ratingChanges["P1"]?.change?.toDouble() ?: 0.0
        val p2Delta = result.response.ratingChanges["P2"]?.change?.toDouble() ?: 0.0

        val (expectedP1, expectedP2) =
            expectedDeltas(
                p1 = p1.toDouble(),
                p2 = p2.toDouble(),
                gamesWon = gamesWon,
                gamesLost = gamesLost,
            )
        p1Delta shouldBe (expectedP1 plusOrMinus TOLERANCE)
        p2Delta shouldBe (expectedP2 plusOrMinus TOLERANCE)

        val clamped = abs(p1Delta + p2Delta) > ZERO_SUM_EPSILON
        return String.format(Locale.US, "%+8.4f", p1Delta) + (if (clamped) "*" else " ")
    }

    /**
     * The master formula from first principles: change = K × dominance × scale × sign,
     * scale by upset vs competitive path, then boundary clamping at 1.0/7.0.
     */
    private fun expectedDeltas(
        p1: Double,
        p2: Double,
        gamesWon: Int,
        gamesLost: Int,
    ): Pair<Double, Double> {
        val dominance = (gamesWon - gamesLost).toDouble() / (gamesWon + gamesLost)
        val normalizedGap = abs(p1 - p2) / NTRP_RANGE
        val isUpset = p1 < p2
        val scale =
            if (isUpset) {
                normalizedGap / THRESHOLD_PCT * UPSET_MULTIPLIER
            } else {
                max(0.0, (THRESHOLD_PCT - normalizedGap) / THRESHOLD_PCT)
            }
        val rawChange = K_NTRP * dominance * scale
        val p1Delta = min(NTRP_MAX, p1 + rawChange) - p1
        val p2Delta = max(NTRP_MIN, p2 - rawChange) - p2
        return p1Delta to p2Delta
    }
}
