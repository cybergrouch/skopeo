// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.calculator.impl.v1

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Exhaustive NTRP matchup matrix: every 0.5-step level pairing (1.0–7.0, 13 levels,
 * 169 ordered matchups) crossed with every legal single-set score. P1 (the row) is always
 * the match winner, so cells below the diagonal are upsets and cells above it are favorite
 * wins. Each cell shows **both** deltas for that match — `P1Δ / P2Δ` — so the winner's gain
 * and the loser's loss are visible side by side (P1's gain on the left, P2's loss on the right).
 *
 * Every cell is verified against the master formula computed from first principles
 * (change = K × dominance × scale × sign, then boundary clamping at 1.0/7.0) for both players,
 * so this doubles as a property test of the calculator across the entire input space.
 *
 * Outputs: a fixed-width text report at /tmp/ntrp_matchup_matrix.txt and a Markdown report at
 * presentations/ntrp_matchup_matrix.md ('*' = boundary clamping broke zero-sum).
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

    /** One computed matchup (P1 wins): both deltas, and whether clamping broke zero-sum. */
    private data class Cell(
        val p1Delta: Double,
        val p2Delta: Double,
        val clamped: Boolean,
    )

    @Test
    fun generateNtrpMatchupMatrix() {
        val matrices =
            TestScenarios.allSingleSetScores.map { (gamesWon, gamesLost) ->
                computeScore(gamesWon = gamesWon, gamesLost = gamesLost)
            }

        val text = renderText(matrices = matrices)
        println(message = text)
        File("/tmp/ntrp_matchup_matrix.txt").writeText(text = text)

        val markdown = renderMarkdown(matrices = matrices)
        val mdFile = File("presentations/ntrp_matchup_matrix.md")
        mdFile.parentFile?.mkdirs()
        mdFile.writeText(text = markdown)

        println(message = "\nResults written to /tmp/ntrp_matchup_matrix.txt and ${mdFile.path}")
    }

    /** All cells for one set score (P1 always the winner), computed and asserted. */
    private fun computeScore(
        gamesWon: Int,
        gamesLost: Int,
    ): ScoreMatrix {
        val levels = TestScenarios.allNtrpLevels
        val dominance = (gamesWon - gamesLost).toDouble() / (gamesWon + gamesLost)
        val rows =
            levels.map { p1 ->
                p1 to levels.map { p2 -> computeCell(p1 = p1, p2 = p2, gamesWon = gamesWon, gamesLost = gamesLost) }
            }
        return ScoreMatrix(gamesWon = gamesWon, gamesLost = gamesLost, dominance = dominance, levels = levels, rows = rows)
    }

    /**
     * Run one matchup (P1 beats P2 gamesWon–gamesLost) through the real calculator and verify
     * both players' deltas against the independently computed expectation.
     */
    private fun computeCell(
        p1: String,
        p2: String,
        gamesWon: Int,
        gamesLost: Int,
    ): Cell {
        val result =
            calculator.calculate(
                request = createSinglesRequest(p1Rating = p1, p2Rating = p2, p1Games = gamesWon, p2Games = gamesLost, winner = "T1"),
            )
        val p1Delta = result.response.ratingChanges["P1"]?.change?.toDouble() ?: 0.0
        val p2Delta = result.response.ratingChanges["P2"]?.change?.toDouble() ?: 0.0

        val (expectedP1, expectedP2) =
            expectedChange(winnerRating = p1.toDouble(), loserRating = p2.toDouble(), gamesWon = gamesWon, gamesLost = gamesLost)
        p1Delta shouldBe (expectedP1 plusOrMinus TOLERANCE)
        p2Delta shouldBe (expectedP2 plusOrMinus TOLERANCE)

        return Cell(p1Delta = p1Delta, p2Delta = p2Delta, clamped = abs(x = p1Delta + p2Delta) > ZERO_SUM_EPSILON)
    }

    /**
     * The master formula from first principles: change = K × dominance × scale × sign,
     * scaled by the upset vs competitive path, then boundary clamping at 1.0/7.0. Returns
     * (winner's delta, loser's delta).
     */
    private fun expectedChange(
        winnerRating: Double,
        loserRating: Double,
        gamesWon: Int,
        gamesLost: Int,
    ): Pair<Double, Double> {
        val dominance = (gamesWon - gamesLost).toDouble() / (gamesWon + gamesLost)
        val normalizedGap = abs(x = winnerRating - loserRating) / NTRP_RANGE
        val isUpset = winnerRating < loserRating
        val scale =
            if (isUpset) {
                normalizedGap / THRESHOLD_PCT * UPSET_MULTIPLIER
            } else {
                max(a = 0.0, b = (THRESHOLD_PCT - normalizedGap) / THRESHOLD_PCT)
            }
        val rawChange = K_NTRP * dominance * scale
        val winnerDelta = min(a = NTRP_MAX, b = winnerRating + rawChange) - winnerRating
        val loserDelta = max(a = NTRP_MIN, b = loserRating - rawChange) - loserRating
        return winnerDelta to loserDelta
    }

    private fun cellText(cell: Cell): String =
        String.format(Locale.US, "%+8.4f /%+8.4f", cell.p1Delta, cell.p2Delta) + (if (cell.clamped) "*" else " ")

    private fun renderText(matrices: List<ScoreMatrix>): String =
        buildString {
            appendLine(value = "NTRP MATCHUP MATRIX")
            appendLine(value = "P1 (row) is always the winner; columns are P2's rating; cell = P1's delta / P2's delta.")
            appendLine(value = "Below the diagonal P1 is the underdog (upset); above it P1 is the favorite.")
            appendLine(value = "'*' = boundary clamping at 1.0/7.0 applied (zero-sum intentionally broken).")
            appendLine(value = "Constants: K=$K_NTRP, threshold=$THRESHOLD_PCT, upset multiplier=$UPSET_MULTIPLIER, range=$NTRP_RANGE")
            matrices.forEach { m ->
                val dom = String.format(Locale.US, "%.3f", m.dominance)
                appendLine()
                appendLine(value = "=== Score ${m.gamesWon}-${m.gamesLost} (dominance $dom) ===")
                appendLine(value = "P1\\P2 " + m.levels.joinToString(separator = "") { level -> String.format(Locale.US, "%19s", level) })
                m.rows.forEach { (p1, cells) ->
                    appendLine(
                        value = String.format(Locale.US, "%-6s", p1) + cells.joinToString(separator = "") { cell -> cellText(cell = cell) },
                    )
                }
            }
        }

    private fun cellMarkdown(cell: Cell): String =
        String.format(Locale.US, "%+.4f / %+.4f", cell.p1Delta, cell.p2Delta) + (if (cell.clamped) " \\*" else "")

    private fun renderMarkdown(matrices: List<ScoreMatrix>): String =
        buildString {
            appendLine(value = "# NTRP Matchup Matrix")
            appendLine()
            appendLine(value = "P1 (row) is always the winner; columns are P2's rating. Each cell is **`P1's delta / P2's delta`**")
            appendLine(value = "for that match — the winner's gain on the left, the loser's loss on the right. Below the diagonal")
            appendLine(value = "P1 is the underdog (upset); above it P1 is the favorite. `\\*` marks a cell where boundary")
            appendLine(value = "clamping at 1.0/7.0 broke zero-sum.")
            appendLine()
            appendLine(value = "Constants: K = $K_NTRP, competitive threshold = $THRESHOLD_PCT,")
            appendLine(value = "upset multiplier = $UPSET_MULTIPLIER, range = $NTRP_RANGE.")
            matrices.forEach { m ->
                val dom = String.format(Locale.US, "%.3f", m.dominance)
                appendLine()
                appendLine(value = "## Score ${m.gamesWon}-${m.gamesLost} (dominance $dom)")
                appendLine()
                appendLine(value = "| P1 \\ P2 | " + m.levels.joinToString(separator = " | ") + " |")
                appendLine(value = "|" + "---|".repeat(n = m.levels.size + 1))
                m.rows.forEach { (p1, cells) ->
                    appendLine(value = "| **$p1** | " + cells.joinToString(separator = " | ") { cell -> cellMarkdown(cell = cell) } + " |")
                }
            }
        }

    /** All computed cells for one set score (P1 the winner). */
    private data class ScoreMatrix(
        val gamesWon: Int,
        val gamesLost: Int,
        val dominance: Double,
        val levels: List<String>,
        val rows: List<Pair<String, List<Cell>>>,
    )
}
