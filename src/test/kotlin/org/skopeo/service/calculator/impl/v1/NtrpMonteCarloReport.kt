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
 * report, not a test). For each starting level (1.0–7.0) and each opponent-mix scenario we run
 * many independent players through N randomized matches, feeding every result through the real
 * calculator, and summarize the distribution of the final rating.
 *
 * ## Model (all parameters are constants below, tune freely)
 *
 * **Opponent gap** (relative to the player's *current* rating; direction above/below is 50/50,
 * flipped when it would fall outside 1.0–7.0):
 *   - Scenario "within 0.5": [P_WITHIN_HALF_NEAR] of opponents are within a 0.5 gap, the rest 0.5–1.0.
 *   - Scenario "beyond 0.5": the majority are 0.5–1.0 away (only [P_WITHIN_HALF_FAR] within 0.5).
 *
 * **Outcome** depends on the rating difference δ = player − opponent:
 *   - P(player wins) = logistic(δ / [WIN_SCALE]).
 *   - Given the outcome, the match is *dominant* with logistic(±δ / [DOM_SCALE]) (the favorite is
 *     more likely to dominate), otherwise *competitive*. This yields the four buckets requested:
 *     DOMINANT_WIN (dominance > 0.6), COMPETITIVE_WIN (≈0.5), COMPETITIVE_LOSS, DOMINANT_LOSS.
 *   - A bucket maps to a concrete set score ([DOMINANT_SCORES] / [COMPETITIVE_SCORES]); NTRP's
 *     0.5 game-margin discretization means there is no integer score strictly between 0.5 and 0.6,
 *     so "dominant" is ≥0.714 (6-1/6-0) and "competitive" is ≤0.5 (6-2/6-3/6-4/7-5).
 *
 * The run is seeded ([SEED]) so the report is reproducible. Outputs: /tmp/ntrp_montecarlo.txt and
 * presentations/ntrp_montecarlo.md.
 */
class NtrpMonteCarloReport {
    private val calculator = PerformanceBasedRankingCalculatorImpl()

    companion object {
        private const val SEED = 20_260_624L
        private const val TRIALS = 5_000
        private const val MATCHES = 20
        private const val NTRP_MIN = 1.0
        private const val NTRP_MAX = 7.0

        // Logistic scales: smaller = sharper dependence on the rating gap.
        private const val WIN_SCALE = 0.5
        private const val DOM_SCALE = 0.7

        private const val HALF_GAP = 0.5
        private const val P_WITHIN_HALF_NEAR = 0.7 // "majority within 0.5"
        private const val P_WITHIN_HALF_FAR = 0.3 // "majority 0.5–1.0"

        // (winnerGames, loserGames) per bucket — dominance = (w-l)/(w+l).
        private val DOMINANT_SCORES = listOf(6 to 0, 6 to 1) // 1.000, 0.714
        private val COMPETITIVE_SCORES = listOf(6 to 2, 6 to 3, 6 to 4, 7 to 5) // 0.500, 0.333, 0.200, 0.167

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
        DOMINANT_LOSS(label = "Dominant loss"),
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

    fun generateMonteCarloReport() {
        val results = Scenario.entries.associateWith { scenario -> simulateScenario(scenario = scenario) }

        val text = renderText(results = results)
        println(message = text)
        File("/tmp/ntrp_montecarlo.txt").writeText(text = text)

        val markdown = renderMarkdown(results = results)
        val mdFile = File("presentations/ntrp_montecarlo.md")
        mdFile.parentFile?.mkdirs()
        mdFile.writeText(text = markdown)

        println(message = "\nResults written to /tmp/ntrp_montecarlo.txt and ${mdFile.path}")
    }

    private fun simulateScenario(scenario: Scenario): List<LevelResult> =
        TestScenarios.allNtrpLevels.map { level ->
            // Each level/scenario gets its own seeded RNG so the report is reproducible regardless of order.
            val rng = Random(seed = SEED + level.hashCode())
            val finals = DoubleArray(size = TRIALS)
            val counts = Outcome.entries.associateWith { 0 }.toMutableMap()
            var matchesPlayed = 0
            repeat(times = TRIALS) { trial ->
                var rating = level.toDouble()
                repeat(times = MATCHES) {
                    val (outcome, newRating) = playMatch(rating = rating, scenario = scenario, rng = rng)
                    counts[outcome] = (counts[outcome] ?: 0) + 1
                    matchesPlayed += 1
                    rating = newRating
                }
                finals[trial] = rating
            }
            LevelResult(
                startLevel = level,
                stats = statsOf(values = finals),
                outcomeShare = counts.mapValues { (_, n) -> n.toDouble() / matchesPlayed },
            )
        }

    /** One match: pick an opponent, sample the outcome, run it through the calculator, return the new rating. */
    private fun playMatch(
        rating: Double,
        scenario: Scenario,
        rng: Random,
    ): Pair<Outcome, Double> {
        val opponent = pickOpponent(rating = rating, scenario = scenario, rng = rng)
        val delta = rating - opponent
        val playerWins = rng.nextDouble() < logistic(x = delta, scale = WIN_SCALE)

        val outcome: Outcome
        val p1Games: Int
        val p2Games: Int
        val winner: String
        if (playerWins) {
            val dominant = rng.nextDouble() < logistic(x = delta, scale = DOM_SCALE)
            outcome = if (dominant) Outcome.DOMINANT_WIN else Outcome.COMPETITIVE_WIN
            val (w, l) = pickScore(dominant = dominant, rng = rng)
            p1Games = w
            p2Games = l
            winner = "T1"
        } else {
            val opponentDominant = rng.nextDouble() < logistic(x = -delta, scale = DOM_SCALE)
            outcome = if (opponentDominant) Outcome.DOMINANT_LOSS else Outcome.COMPETITIVE_LOSS
            val (w, l) = pickScore(dominant = opponentDominant, rng = rng)
            p1Games = l
            p2Games = w
            winner = "T2"
        }

        val result =
            calculator.calculate(
                request =
                    createSinglesRequest(
                        p1Rating = fmtRating(value = rating),
                        p2Rating = fmtRating(value = opponent),
                        p1Games = p1Games,
                        p2Games = p2Games,
                        winner = winner,
                    ),
            )
        val newRating = result.response.ratingChanges["P1"]?.newRating?.value?.toDouble() ?: rating
        return outcome to newRating
    }

    private fun pickOpponent(
        rating: Double,
        scenario: Scenario,
        rng: Random,
    ): Double {
        val within = rng.nextDouble() < scenario.pWithinHalf
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

    private fun renderText(results: Map<Scenario, List<LevelResult>>): String =
        buildString {
            appendLine(value = "NTRP RATING MONTE CARLO ($TRIALS players × $MATCHES matches each, seed $SEED)")
            appendLine(value = "Final-rating distribution by starting level.")
            appendLine(value = "Win prob = logistic(δ/$WIN_SCALE); dominance split via δ/$DOM_SCALE.")
            results.forEach { (scenario, levels) ->
                appendLine()
                appendLine(value = "=== Scenario: ${scenario.label} (P[within 0.5]=${scenario.pWithinHalf}) ===")
                appendLine(value = "start   mean     sd      p5     p25     p50     p75     p95     min     max")
                levels.forEach { lr ->
                    appendLine(value = textRow(lr = lr))
                }
                appendLine(value = "-- outcome mix (share of all matches) --")
                appendLine(value = "start   domWin  compWin compLoss domLoss")
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

    private fun renderMarkdown(results: Map<Scenario, List<LevelResult>>): String =
        buildString {
            appendLine(value = "# NTRP Rating Monte Carlo")
            appendLine()
            appendLine(value = "$TRIALS simulated players per starting level, each playing $MATCHES randomized matches")
            appendLine(value = "(seed $SEED, reproducible). Win probability is `logistic(δ / $WIN_SCALE)` where δ = player − opponent;")
            appendLine(value = "given the outcome the match is dominant with `logistic(±δ / $DOM_SCALE)`, else competitive.")
            appendLine(value = "Dominant scores ≥ 0.714 (6-1/6-0); competitive ≤ 0.5 (6-2/6-3/6-4/7-5).")
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
                appendLine(value = "| Start | Dominant win | Competitive win | Competitive loss | Dominant loss |")
                appendLine(value = "|---|---|---|---|---|")
                levels.forEach { lr -> appendLine(value = markdownOutcomeRow(lr = lr)) }
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
