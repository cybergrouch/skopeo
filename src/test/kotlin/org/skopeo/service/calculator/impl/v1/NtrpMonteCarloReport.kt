// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.calculator.impl.v1

import java.io.File
import java.util.Locale
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
 * Outputs: /tmp/ntrp_montecarlo.txt and presentations/ntrp_montecarlo.md.
 */
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

        println(message = "\nResults written to /tmp/ntrp_montecarlo.txt and ${mdFile.path}")
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
            appendLine(value = "# Analysis 1 — gap-driven scenarios")
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
