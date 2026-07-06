// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.calculator.impl.v1

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Monte Carlo simulation of how a player's NTRP rating evolves over N matches (a runnable
 * report, not a test). Two analyses, both seeded for reproducibility:
 *
 * 1. **Gap scenarios** — opponents are drawn near the player and the outcome is derived from the
 *    rating gap; we report the final-rating distribution per starting level for two opponent mixes
 *    (majority within a 0.5 gap vs majority 0.5–1.0 away).
 * 2. **Outcome-class experiment** — the opponent gap is held neutral (uniform 0–1.0) and the
 *    per-match outcome mix is *fixed* by a class, so each class's effect on the rating is isolated.
 *    Classes: equal; more wins (dom = comp); more competitive wins; more dominant wins; more losses
 *    (dom = comp); more competitive losses; more dominated.
 *
 * The four outcomes are DOMINANT_WIN, COMPETITIVE_WIN, COMPETITIVE_LOSS, and DOMINATED (the inverse
 * of a dominant win — the player is crushed, e.g. 0-6/1-6/2-6). Dominant scores have a margin ≥ 0.5
 * (6-0/6-1/6-2), competitive ≤ 0.333 (6-3/6-4/7-5). Every result runs through the real calculator.
 *
 * Outputs: /tmp/ntrp_montecarlo.txt, presentations/ntrp_montecarlo.md, and two PNG charts in
 * presentations/ (a class-effect heatmap and an Analysis-1 median-vs-start check).
 */
@Suppress("DEPRECATION") // exercises the deprecated v1 calculator; removed together with v1
class NtrpMonteCarloReport {
    private val calculator = PerformanceBasedRankingCalculatorImpl()

    companion object {
        private const val SEED = 20_260_624L
        private const val TRIALS = 3_000
        private const val MATCHES = 20
        private const val NTRP_MIN = 1.0
        private const val NTRP_MAX = 7.0

        // Logistic scales: smaller = sharper dependence on the rating gap.
        private const val WIN_SCALE = 0.5
        private const val DOM_SCALE = 0.7

        private const val HALF_GAP = 0.5
        private const val P_WITHIN_HALF_NEAR = 0.7 // "majority within 0.5"
        private const val P_WITHIN_HALF_FAR = 0.3 // "majority 0.5–1.0"
        private const val NEUTRAL_P_WITHIN_HALF = 0.5 // class experiment: uniform 0–1.0 opponent gap

        // (winnerGames, loserGames) per bucket — margin = (w-l)/(w+l).
        private val DOMINANT_SCORES = listOf(6 to 0, 6 to 1, 6 to 2) // 1.000, 0.714, 0.500
        private val COMPETITIVE_SCORES = listOf(6 to 3, 6 to 4, 7 to 5) // 0.333, 0.200, 0.167

        private val PERCENTILES = listOf(5, 25, 50, 75, 95)
        private const val PERCENT = 100.0

        private const val CHART_HEATMAP = "montecarlo_class_heatmap.png"
        private const val CHART_MEDIAN = "montecarlo_median_check.png"
        private val INK = Color(33, 33, 33)
        private val AXIS = Color(90, 90, 90)
        private val GRID = Color(232, 232, 232)
        private val GRID_DARK = Color(150, 150, 150)
    }

    enum class Scenario(
        val label: String,
        val pWithinHalf: Double,
    ) {
        WITHIN_HALF(label = "Majority within 0.5 gap", pWithinHalf = P_WITHIN_HALF_NEAR),
        BEYOND_HALF(label = "Majority 0.5–1.0 gap", pWithinHalf = P_WITHIN_HALF_FAR),
    }

    enum class Outcome(val label: String) {
        DOMINANT_WIN(label = "Dominant win"),
        COMPETITIVE_WIN(label = "Competitive win"),
        COMPETITIVE_LOSS(label = "Competitive loss"),
        DOMINATED(label = "Dominated"),
    }

    /** A fixed per-match outcome mix. [weights] are P(DW, CW, CL, Dominated) in [Outcome] order. */
    enum class OutcomeClass(
        val label: String,
        val short: String,
        val weights: List<Double>,
    ) {
        EQUAL(label = "Equal (all four 25%)", short = "Equal", weights = listOf(0.25, 0.25, 0.25, 0.25)),
        MORE_WINS(label = "More wins (dom = comp)", short = "+Win", weights = listOf(0.35, 0.35, 0.15, 0.15)),
        MORE_COMPETITIVE_WINS(label = "More competitive wins", short = "+CWin", weights = listOf(0.20, 0.50, 0.15, 0.15)),
        MORE_DOMINANT_WINS(label = "More dominant wins", short = "+DWin", weights = listOf(0.50, 0.20, 0.15, 0.15)),
        MORE_LOSSES(label = "More losses (dom = comp)", short = "+Loss", weights = listOf(0.15, 0.15, 0.35, 0.35)),
        MORE_COMPETITIVE_LOSSES(label = "More competitive losses", short = "+CLoss", weights = listOf(0.15, 0.15, 0.50, 0.20)),
        MORE_DOMINATED(label = "More dominated losses", short = "+Domd", weights = listOf(0.15, 0.15, 0.20, 0.50)),
    }

    private data class Stats(
        val mean: Double,
        val sd: Double,
        val min: Double,
        val max: Double,
        val percentiles: Map<Int, Double>,
    )

    private data class LevelResult(
        val startLevel: String,
        val stats: Stats,
        val outcomeShare: Map<Outcome, Double>,
    )

    private data class ClassResult(
        val meanEnd: Double,
        val sd: Double,
        val meanChange: Double,
    )

    fun generateMonteCarloReport() {
        val results = Scenario.entries.associateWith { scenario -> simulateScenario(scenario = scenario) }
        val classResults =
            TestScenarios.allNtrpLevels.map { level ->
                level to OutcomeClass.entries.associateWith { cls -> simulateClass(level = level, outcomeClass = cls) }
            }

        val text = renderText(results = results, classResults = classResults)
        println(message = text)
        File("/tmp/ntrp_montecarlo.txt").writeText(text = text)

        val markdown = renderMarkdown(results = results, classResults = classResults)
        val mdFile = File("presentations/ntrp_montecarlo.md")
        mdFile.parentFile?.mkdirs()
        mdFile.writeText(text = markdown)

        drawClassHeatmap(classResults = classResults, file = File(mdFile.parentFile, CHART_HEATMAP))
        drawMedianCheck(results = results, file = File(mdFile.parentFile, CHART_MEDIAN))

        println(message = "\nResults written to /tmp/ntrp_montecarlo.txt, ${mdFile.path}, and 2 PNG charts in presentations/")
    }

    // --- Analysis 1: gap-driven scenarios ---

    private fun simulateScenario(scenario: Scenario): List<LevelResult> =
        TestScenarios.allNtrpLevels.map { level ->
            val rng = Random(seed = SEED + level.hashCode())
            val finals = DoubleArray(size = TRIALS)
            val counts = Outcome.entries.associateWith { 0 }.toMutableMap()
            var matchesPlayed = 0
            repeat(times = TRIALS) { trial ->
                var rating = level.toDouble()
                repeat(times = MATCHES) {
                    val opponent = pickOpponent(rating = rating, pWithinHalf = scenario.pWithinHalf, rng = rng)
                    val outcome = gapOutcome(rating = rating, opponent = opponent, rng = rng)
                    counts[outcome] = (counts[outcome] ?: 0) + 1
                    matchesPlayed += 1
                    rating = applyMatch(rating = rating, opponent = opponent, outcome = outcome, rng = rng)
                }
                finals[trial] = rating
            }
            LevelResult(
                startLevel = level,
                stats = statsOf(values = finals),
                outcomeShare = counts.mapValues { (_, n) -> n.toDouble() / matchesPlayed },
            )
        }

    /** Outcome derived from the rating gap: the favorite wins more and dominates more. */
    private fun gapOutcome(
        rating: Double,
        opponent: Double,
        rng: Random,
    ): Outcome {
        val delta = rating - opponent
        return if (rng.nextDouble() < logistic(x = delta, scale = WIN_SCALE)) {
            if (rng.nextDouble() < logistic(x = delta, scale = DOM_SCALE)) Outcome.DOMINANT_WIN else Outcome.COMPETITIVE_WIN
        } else {
            if (rng.nextDouble() < logistic(x = -delta, scale = DOM_SCALE)) Outcome.DOMINATED else Outcome.COMPETITIVE_LOSS
        }
    }

    // --- Analysis 2: fixed outcome-class experiment ---

    private fun simulateClass(
        level: String,
        outcomeClass: OutcomeClass,
    ): ClassResult {
        val rng = Random(seed = SEED + level.hashCode() + outcomeClass.ordinal)
        val finals = DoubleArray(size = TRIALS)
        repeat(times = TRIALS) { trial ->
            var rating = level.toDouble()
            repeat(times = MATCHES) {
                val opponent = pickOpponent(rating = rating, pWithinHalf = NEUTRAL_P_WITHIN_HALF, rng = rng)
                val outcome = classOutcome(weights = outcomeClass.weights, rng = rng)
                rating = applyMatch(rating = rating, opponent = opponent, outcome = outcome, rng = rng)
            }
            finals[trial] = rating
        }
        val stats = statsOf(values = finals)
        return ClassResult(meanEnd = stats.mean, sd = stats.sd, meanChange = stats.mean - level.toDouble())
    }

    /** Draw an outcome from a fixed categorical distribution. */
    private fun classOutcome(
        weights: List<Double>,
        rng: Random,
    ): Outcome {
        var threshold = rng.nextDouble() * weights.sum()
        for (outcome in Outcome.entries) {
            threshold -= weights[outcome.ordinal]
            if (threshold < 0) {
                return outcome
            }
        }
        return Outcome.entries.last()
    }

    // --- Shared match machinery ---

    /** Map an outcome to a concrete set score, then run it through the calculator; return the new rating. */
    private fun applyMatch(
        rating: Double,
        opponent: Double,
        outcome: Outcome,
        rng: Random,
    ): Double {
        val dominant = outcome == Outcome.DOMINANT_WIN || outcome == Outcome.DOMINATED
        val playerWins = outcome == Outcome.DOMINANT_WIN || outcome == Outcome.COMPETITIVE_WIN
        val (w, l) = pickScore(dominant = dominant, rng = rng)
        val result =
            calculator.calculate(
                request =
                    createSinglesRequest(
                        p1Rating = fmtRating(value = rating),
                        p2Rating = fmtRating(value = opponent),
                        p1Games = if (playerWins) w else l,
                        p2Games = if (playerWins) l else w,
                        winner = if (playerWins) "T1" else "T2",
                    ),
            )
        return result.response.ratingChanges["P1"]?.newRating?.value?.toDouble() ?: rating
    }

    private fun pickOpponent(
        rating: Double,
        pWithinHalf: Double,
        rng: Random,
    ): Double {
        val within = rng.nextDouble() < pWithinHalf
        val gap = if (within) rng.nextDouble() * HALF_GAP else HALF_GAP + rng.nextDouble() * HALF_GAP
        val above =
            when {
                rating + gap > NTRP_MAX -> false
                rating - gap < NTRP_MIN -> true
                else -> rng.nextBoolean()
            }
        val raw = if (above) rating + gap else rating - gap
        return raw.coerceIn(minimumValue = NTRP_MIN, maximumValue = NTRP_MAX)
    }

    private fun pickScore(
        dominant: Boolean,
        rng: Random,
    ): Pair<Int, Int> {
        val pool = if (dominant) DOMINANT_SCORES else COMPETITIVE_SCORES
        return pool[rng.nextInt(until = pool.size)]
    }

    private fun logistic(
        x: Double,
        scale: Double,
    ): Double = 1.0 / (1.0 + exp(x = -x / scale))

    private fun fmtRating(value: Double): String =
        String.format(Locale.US, "%.6f", value.coerceIn(minimumValue = NTRP_MIN, maximumValue = NTRP_MAX))

    private fun statsOf(values: DoubleArray): Stats {
        val mean = values.average()
        val variance = values.sumOf { v -> (v - mean) * (v - mean) } / values.size
        val sorted = values.sorted()
        val percentiles =
            PERCENTILES.associateWith { p ->
                val index = (p / PERCENT * (sorted.size - 1)).roundToInt()
                sorted[index]
            }
        return Stats(mean = mean, sd = sqrt(x = variance), min = sorted.first(), max = sorted.last(), percentiles = percentiles)
    }

    // --- Charts (java.awt, no external dependency) ---

    /**
     * Diverging heatmap of the outcome-class experiment: rows = starting level, columns = class,
     * each cell coloured green (rating rises) → white (~unchanged) → red (rating falls).
     */
    private fun drawClassHeatmap(
        classResults: List<Pair<String, Map<OutcomeClass, ClassResult>>>,
        file: File,
    ) {
        val classes = OutcomeClass.entries
        val cellW = 96
        val cellH = 38
        val left = 64
        val top = 100
        val width = left + classes.size * cellW + 24
        val height = top + classResults.size * cellH + 24
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = newCanvas(img = img, width = width, height = height)

        g.color = INK
        g.font = Font("SansSerif", Font.BOLD, 16)
        g.drawString("Effect of performance on rating: mean Δ after $MATCHES matches", 16, 30)
        g.font = Font("SansSerif", Font.PLAIN, 12)
        g.drawString("Green = rating rises, red = rating falls. Rows = starting NTRP level; columns = performance class.", 16, 52)

        val maxAbs = classResults.maxOf { (_, byClass) -> classes.maxOf { c -> abs(x = byClass.getValue(key = c).meanChange) } }

        g.font = Font("SansSerif", Font.BOLD, 12)
        classes.forEachIndexed { ci, c ->
            drawCentered(g = g, text = c.short, cx = left + ci * cellW + cellW / 2, cy = top - 10)
        }
        classResults.forEachIndexed { ri, (level, byClass) ->
            val y = top + ri * cellH
            g.color = INK
            g.font = Font("SansSerif", Font.BOLD, 12)
            g.drawString(level, 16, y + cellH / 2 + 4)
            classes.forEachIndexed { ci, c ->
                val x = left + ci * cellW
                val v = byClass.getValue(key = c).meanChange
                g.color = divergingColor(value = v, maxAbs = maxAbs)
                g.fillRect(x, y, cellW - 2, cellH - 2)
                g.color = if (abs(x = v) / maxAbs > 0.55) Color.WHITE else INK
                g.font = Font("SansSerif", Font.PLAIN, 12)
                drawCentered(g = g, text = String.format(Locale.US, "%+.2f", v), cx = x + (cellW - 2) / 2, cy = y + cellH / 2 + 4)
            }
        }
        g.dispose()
        ImageIO.write(img, "png", file)
    }

    /**
     * Analysis-1 expectation check: median final rating vs starting level for both opponent mixes,
     * against the dashed y = x "no change" line. Interior points landing on the line show the
     * ratings are unbiased on average; the ends bend inward (boundary regression at 1.0 / 7.0).
     */
    private fun drawMedianCheck(
        results: Map<Scenario, List<LevelResult>>,
        file: File,
    ) {
        val width = 720
        val height = 560
        val left = 70
        val top = 70
        val plotW = width - left - 40
        val plotH = height - top - 70
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = newCanvas(img = img, width = width, height = height)

        fun sx(v: Double): Int = (left + (v - NTRP_MIN) / (NTRP_MAX - NTRP_MIN) * plotW).roundToInt()

        fun sy(v: Double): Int = (top + plotH - (v - NTRP_MIN) / (NTRP_MAX - NTRP_MIN) * plotH).roundToInt()

        g.color = INK
        g.font = Font("SansSerif", Font.BOLD, 16)
        g.drawString("Analysis 1 check: median final rating vs starting level", 16, 30)
        g.font = Font("SansSerif", Font.PLAIN, 12)
        g.drawString("Dashed line = no change (y = x). Interior medians sit on it: ratings are unbiased on average.", 16, 52)

        g.font = Font("SansSerif", Font.PLAIN, 11)
        for (tick in 1..NTRP_MAX.toInt()) {
            val gx = sx(v = tick.toDouble())
            val gy = sy(v = tick.toDouble())
            g.color = GRID
            g.drawLine(gx, top, gx, top + plotH)
            g.drawLine(left, gy, left + plotW, gy)
            g.color = AXIS
            drawCentered(g = g, text = tick.toString(), cx = gx, cy = top + plotH + 18)
            g.drawString(tick.toString(), left - 22, gy + 4)
        }
        g.color = AXIS
        g.drawRect(left, top, plotW, plotH)
        drawCentered(g = g, text = "Starting NTRP level", cx = left + plotW / 2, cy = height - 24)
        val pivotY = (top + plotH / 2).toDouble()
        g.rotate(-Math.PI / 2.0, 18.0, pivotY)
        drawCentered(g = g, text = "Median final rating", cx = (top + plotH / 2), cy = 22)
        g.rotate(Math.PI / 2.0, 18.0, pivotY)

        g.color = GRID_DARK
        g.stroke = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(6f, 6f), 0f)
        g.drawLine(sx(v = NTRP_MIN), sy(v = NTRP_MIN), sx(v = NTRP_MAX), sy(v = NTRP_MAX))
        g.stroke = BasicStroke(2.5f)

        val palette = listOf(Color(33, 118, 199), Color(214, 118, 32))
        results.entries.forEachIndexed { si, (_, levels) ->
            g.color = palette[si % palette.size]
            var prevX = -1
            var prevY = -1
            levels.forEach { lr ->
                val px = sx(v = lr.startLevel.toDouble())
                val py = sy(v = lr.stats.percentiles[50] ?: lr.stats.mean)
                if (prevX >= 0) {
                    g.drawLine(prevX, prevY, px, py)
                }
                g.fillOval(px - 4, py - 4, 8, 8)
                prevX = px
                prevY = py
            }
        }

        g.font = Font("SansSerif", Font.PLAIN, 12)
        drawScenarioLegend(g = g, x = left + plotW - 232, yTop = top + 8, palette = palette, labels = results.keys.map { it.label })
        g.dispose()
        ImageIO.write(img, "png", file)
    }

    private fun drawScenarioLegend(
        g: Graphics2D,
        x: Int,
        yTop: Int,
        palette: List<Color>,
        labels: List<String>,
    ) {
        labels.forEachIndexed { i, label ->
            val ly = yTop + i * 22
            g.color = palette[i % palette.size]
            g.fillRect(x, ly, 14, 14)
            g.color = INK
            g.drawString(label, x + 20, ly + 12)
        }
    }

    private fun newCanvas(
        img: BufferedImage,
        width: Int,
        height: Int,
    ): Graphics2D {
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        return g
    }

    private fun drawCentered(
        g: Graphics2D,
        text: String,
        cx: Int,
        cy: Int,
    ) {
        g.drawString(text, cx - g.fontMetrics.stringWidth(text) / 2, cy)
    }

    private fun divergingColor(
        value: Double,
        maxAbs: Double,
    ): Color {
        val t = (abs(x = value) / maxAbs).coerceIn(minimumValue = 0.0, maximumValue = 1.0)
        return if (value >= 0) {
            Color(lerp(a = 255, b = 56, t = t), lerp(a = 255, b = 158, t = t), lerp(a = 255, b = 92, t = t))
        } else {
            Color(lerp(a = 255, b = 200, t = t), lerp(a = 255, b = 64, t = t), lerp(a = 255, b = 64, t = t))
        }
    }

    private fun lerp(
        a: Int,
        b: Int,
        t: Double,
    ): Int = (a + (b - a) * t).roundToInt()

    private fun emojiFor(value: Double): String =
        when {
            value >= 0.6 -> "🟩🟩"
            value >= 0.15 -> "🟩"
            value > -0.15 -> "⬜"
            value > -0.6 -> "🟥"
            else -> "🟥🟥"
        }

    // --- Rendering ---

    private fun renderText(
        results: Map<Scenario, List<LevelResult>>,
        classResults: List<Pair<String, Map<OutcomeClass, ClassResult>>>,
    ): String =
        buildString {
            appendLine(value = "NTRP RATING MONTE CARLO ($TRIALS players × $MATCHES matches each, seed $SEED)")
            appendLine(value = "Win prob = logistic(δ/$WIN_SCALE); dominance split via δ/$DOM_SCALE.")
            appendLine(value = "pN = Nth percentile of final rating (pN = N% of players finished below it); [p5,p95] is a 90% band.")
            results.forEach { (scenario, levels) ->
                appendLine()
                appendLine(value = "=== Scenario: ${scenario.label} (P[within 0.5]=${scenario.pWithinHalf}) ===")
                appendLine(value = "start   mean     sd      p5     p25     p50     p75     p95     min     max")
                levels.forEach { lr -> appendLine(value = textRow(lr = lr)) }
                appendLine(value = "-- outcome mix (share of all matches) --")
                appendLine(value = "start   domWin  compWin compLoss dominated")
                levels.forEach { lr ->
                    appendLine(
                        value =
                            String.format(Locale.US, "%-6s", lr.startLevel) +
                                Outcome.entries.joinToString(separator = "") { o ->
                                    String.format(Locale.US, "%8.1f%%", (lr.outcomeShare[o] ?: 0.0) * PERCENT)
                                },
                    )
                }
            }
            appendLine(value = classSectionText(classResults = classResults))
        }

    private fun classSectionText(classResults: List<Pair<String, Map<OutcomeClass, ClassResult>>>): String =
        buildString {
            appendLine()
            appendLine(value = "=== OUTCOME-CLASS EXPERIMENT (opponent gap neutral: P[within 0.5]=$NEUTRAL_P_WITHIN_HALF) ===")
            appendLine(value = "Each class fixes the per-match outcome probabilities (DW/CW/CL/Dominated):")
            OutcomeClass.entries.forEach { c ->
                appendLine(
                    value =
                        String.format(Locale.US, "  %-7s %-24s ", c.short, c.label) +
                            Outcome.entries.joinToString(separator = " ") { o -> String.format(Locale.US, "%.2f", c.weights[o.ordinal]) },
                )
            }
            appendLine(value = "-- mean rating change (Δ) after $MATCHES matches --")
            appendLine(
                value = "start " + OutcomeClass.entries.joinToString(separator = "") { c -> String.format(Locale.US, "%8s", c.short) },
            )
            classResults.forEach { (level, byClass) ->
                appendLine(
                    value =
                        String.format(Locale.US, "%-6s", level) +
                            OutcomeClass.entries.joinToString(separator = "") { c ->
                                String.format(Locale.US, "%+8.3f", byClass.getValue(key = c).meanChange)
                            },
                )
            }
            appendLine(value = "-- mean end rating --")
            appendLine(
                value = "start " + OutcomeClass.entries.joinToString(separator = "") { c -> String.format(Locale.US, "%8s", c.short) },
            )
            classResults.forEach { (level, byClass) ->
                appendLine(
                    value =
                        String.format(Locale.US, "%-6s", level) +
                            OutcomeClass.entries.joinToString(separator = "") { c ->
                                String.format(Locale.US, "%8.3f", byClass.getValue(key = c).meanEnd)
                            },
                )
            }
        }

    private fun textRow(lr: LevelResult): String =
        String.format(Locale.US, "%-6s", lr.startLevel) +
            String.format(
                Locale.US,
                "%7.3f %7.3f %7.3f %7.3f %7.3f %7.3f %7.3f %7.3f %7.3f",
                lr.stats.mean,
                lr.stats.sd,
                lr.stats.percentiles[5],
                lr.stats.percentiles[25],
                lr.stats.percentiles[50],
                lr.stats.percentiles[75],
                lr.stats.percentiles[95],
                lr.stats.min,
                lr.stats.max,
            )

    private fun renderMarkdown(
        results: Map<Scenario, List<LevelResult>>,
        classResults: List<Pair<String, Map<OutcomeClass, ClassResult>>>,
    ): String =
        buildString {
            appendLine(value = "# NTRP Rating Monte Carlo")
            appendLine()
            appendLine(value = "$TRIALS simulated players per starting level, each playing $MATCHES randomized matches")
            appendLine(value = "(seed $SEED, reproducible). Win probability is `logistic(δ / $WIN_SCALE)` where δ = player − opponent;")
            appendLine(value = "given the outcome the match is dominant with `logistic(±δ / $DOM_SCALE)`, else competitive.")
            appendLine(value = "Dominant scores ≥ 0.5 (6-0/6-1/6-2); competitive ≤ 0.333 (6-3/6-4/7-5).")
            appendLine(value = "'Dominated' = the player loses by a dominant score (the inverse of a dominant win).")
            appendLine()
            appendLine(value = "**Percentiles** (`pN`) describe the spread of final ratings: `pN` = N% of simulated")
            appendLine(value = "players finished *below* that rating. So p5/p95 bracket the middle 90% of outcomes,")
            appendLine(value = "p25/p75 the middle 50% (interquartile range), and p50 is the median.")
            appendLine()
            appendLine(value = "## Summary charts")
            appendLine()
            appendLine(value = "**What raises vs lowers a rating** — green cells push the rating up, red cells pull it down.")
            appendLine(value = "Bigger margins move it more: dominant wins (+DWin) climb fastest, being dominated (+Domd) falls fastest.")
            appendLine()
            appendLine(value = "![Outcome-class heatmap]($CHART_HEATMAP)")
            appendLine()
            appendLine(value = "**Analysis 1 is as expected on average** — interior medians land on the dashed y = x line")
            appendLine(value = "(rating unchanged on average); only the 1.0 / 7.0 boundaries bend inward.")
            appendLine()
            appendLine(value = "![Analysis 1 median check]($CHART_MEDIAN)")
            appendLine()
            appendLine(value = "# Analysis 1 — gap-driven scenarios")
            appendLine(value = mermaidSection(results = results))
            results.forEach { (scenario, levels) ->
                appendLine()
                appendLine(value = "## ${scenario.label} (P[opponent within 0.5] = ${scenario.pWithinHalf})")
                appendLine()
                appendLine(value = "Final rating after $MATCHES matches:")
                appendLine()
                appendLine(value = "| Start | Mean | SD | p5 | p25 | Median | p75 | p95 | Min | Max |")
                appendLine(value = "|---|---|---|---|---|---|---|---|---|---|")
                levels.forEach { lr -> appendLine(value = markdownStatsRow(lr = lr)) }
                appendLine()
                appendLine(value = "Outcome mix (share of all matches played):")
                appendLine()
                appendLine(value = "| Start | Dominant win | Competitive win | Competitive loss | Dominated |")
                appendLine(value = "|---|---|---|---|---|")
                levels.forEach { lr -> appendLine(value = markdownOutcomeRow(lr = lr)) }
            }
            appendLine(value = classSectionMarkdown(classResults = classResults))
        }

    /**
     * A GitHub-renderable Mermaid summary of Analysis 1: the final-rating p5/median/p95 band vs
     * starting level for the representative scenario. The band holds 90% of players; the median
     * line tracking the diagonal is the "unbiased on average" result.
     */
    private fun mermaidSection(results: Map<Scenario, List<LevelResult>>): String =
        buildString {
            val scenario = Scenario.WITHIN_HALF
            val levels = results.getValue(key = scenario)
            val axis = levels.joinToString(separator = ", ") { lr -> "\"${lr.startLevel}\"" }
            appendLine()
            appendLine(value = "## Distribution at a glance — ${scenario.label}")
            appendLine()
            appendLine(value = "The band between the p5 and p95 lines holds 90% of players; the median is the middle line.")
            appendLine(value = "The median tracks the starting level (unbiased on average) and the band stays roughly ±0.5")
            appendLine(value = "wide, compressing only at the 1.0 / 7.0 boundaries. The other scenario is nearly identical.")
            appendLine()
            appendLine(value = "```mermaid")
            appendLine(value = "xychart-beta")
            appendLine(value = "    title \"Final rating after $MATCHES matches — p95 (top), median, p5 (bottom)\"")
            appendLine(value = "    x-axis \"Starting NTRP level\" [$axis]")
            appendLine(value = "    y-axis \"Final rating\" 1 --> 7")
            appendLine(value = "    line ${mermaidSeries(levels = levels) { lr -> lr.stats.percentiles[95] }}")
            appendLine(value = "    line ${mermaidSeries(levels = levels) { lr -> lr.stats.percentiles[50] }}")
            appendLine(value = "    line ${mermaidSeries(levels = levels) { lr -> lr.stats.percentiles[5] }}")
            appendLine(value = "```")
        }

    private fun mermaidSeries(
        levels: List<LevelResult>,
        value: (LevelResult) -> Double?,
    ): String =
        levels.joinToString(separator = ", ", prefix = "[", postfix = "]") { lr ->
            String.format(Locale.US, "%.3f", value(lr) ?: 0.0)
        }

    private fun classSectionMarkdown(classResults: List<Pair<String, Map<OutcomeClass, ClassResult>>>): String =
        buildString {
            appendLine()
            appendLine(value = "# Analysis 2 — outcome-class experiment")
            appendLine()
            appendLine(value = "The opponent gap is held neutral (uniform 0–1.0; P[within 0.5] = $NEUTRAL_P_WITHIN_HALF) and only the")
            appendLine(value = "per-match outcome mix varies, so each table isolates a class's effect on the rating.")
            appendLine()
            appendLine(value = "Class definitions (per-match probabilities):")
            appendLine()
            appendLine(value = "| Class | Dominant win | Competitive win | Competitive loss | Dominated |")
            appendLine(value = "|---|---|---|---|---|")
            OutcomeClass.entries.forEach { c ->
                appendLine(
                    value =
                        "| ${c.label} | " +
                            Outcome.entries.joinToString(separator = " | ") { o ->
                                String.format(Locale.US, "%.0f%%", c.weights[o.ordinal] * PERCENT)
                            } + " |",
                )
            }
            appendLine()
            appendLine(value = "Mean rating change (Δ) after $MATCHES matches, by starting level × class:")
            appendLine()
            appendClassMatrix(classResults = classResults, signed = true) { it.meanChange }
            appendLine()
            appendLine(value = "At a glance (🟩 rating rises · ⬜ ~unchanged · 🟥 rating falls; doubled = strong move):")
            appendLine()
            appendLine(value = "| Start | " + OutcomeClass.entries.joinToString(separator = " | ") { c -> c.short } + " |")
            appendLine(value = "|---|" + "---|".repeat(n = OutcomeClass.entries.size))
            classResults.forEach { (level, byClass) ->
                appendLine(
                    value =
                        "| **$level** | " +
                            OutcomeClass.entries.joinToString(separator = " | ") { c ->
                                emojiFor(value = byClass.getValue(key = c).meanChange)
                            } + " |",
                )
            }
            appendLine()
            appendLine(value = "Mean end rating, by starting level × class:")
            appendLine()
            appendClassMatrix(classResults = classResults, signed = false) { it.meanEnd }
        }

    private fun StringBuilder.appendClassMatrix(
        classResults: List<Pair<String, Map<OutcomeClass, ClassResult>>>,
        signed: Boolean,
        value: (ClassResult) -> Double,
    ) {
        appendLine(value = "| Start | " + OutcomeClass.entries.joinToString(separator = " | ") { c -> c.short } + " |")
        appendLine(value = "|---|" + "---|".repeat(n = OutcomeClass.entries.size))
        val format = if (signed) "%+.3f" else "%.3f"
        classResults.forEach { (level, byClass) ->
            appendLine(
                value =
                    "| **$level** | " +
                        OutcomeClass.entries.joinToString(separator = " | ") { c ->
                            String.format(Locale.US, format, value(byClass.getValue(key = c)))
                        } + " |",
            )
        }
    }

    private fun markdownStatsRow(lr: LevelResult): String =
        "| **${lr.startLevel}** | " +
            listOf(
                lr.stats.mean,
                lr.stats.sd,
                lr.stats.percentiles[5],
                lr.stats.percentiles[25],
                lr.stats.percentiles[50],
                lr.stats.percentiles[75],
                lr.stats.percentiles[95],
                lr.stats.min,
                lr.stats.max,
            ).joinToString(separator = " | ") { v -> String.format(Locale.US, "%.3f", v) } + " |"

    private fun markdownOutcomeRow(lr: LevelResult): String =
        "| **${lr.startLevel}** | " +
            Outcome.entries.joinToString(separator = " | ") { o ->
                String.format(Locale.US, "%.1f%%", (lr.outcomeShare[o] ?: 0.0) * PERCENT)
            } + " |"
}

/** Run the simulation directly (IDE, or `./gradlew generateMonteCarloReport`). */
fun main() {
    NtrpMonteCarloReport().generateMonteCarloReport()
}
