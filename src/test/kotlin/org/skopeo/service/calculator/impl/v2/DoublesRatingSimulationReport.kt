// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.calculator.impl.v2

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import org.junit.jupiter.api.Test
import org.skopeo.dto.RankingCalculationRequest
import org.skopeo.model.MatchScore
import org.skopeo.model.PlayerProfile
import org.skopeo.model.Rating
import org.skopeo.model.SetScore
import org.skopeo.model.Team
import org.skopeo.model.TeamType
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Monte-Carlo fairness study for the two candidate doubles rating schemes (#256), reusing the real v2
 * per-set engine (each subject-vs-opponent change is obtained from `PerformanceBasedRankingCalculatorImpl`
 * on a synthesised single-set singles request — no re-implementation of the maths). Both schemes score
 * the *same* simulated matches; outcomes depend only on players' hidden true skill, so the comparison is
 * apples-to-apples. See docs/product/DOUBLES_RATING_STUDY.md. Deterministic (seeded); prints a summary.
 *
 * Magic constants (tuning), stdlib/math calls, and the fixed four-player match shape are inherent to a
 * numeric report, so the corresponding style rules are suppressed for this research artefact.
 */
@Suppress("MagicNumber", "NamedArguments", "LongParameterList")
class DoublesRatingSimulationReport {
    private val calculator = PerformanceBasedRankingCalculatorImpl()

    private companion object {
        const val SEED = 20260706L
        const val PLAYERS = 1000
        const val ROUNDS = 50
        const val SKILL_MEAN = 3.8
        const val SKILL_SD = 0.9
        const val SKILL_MIN = 1.5
        const val SKILL_MAX = 6.0
        const val WIN_STEEPNESS = 1.2
    }

    /** One subject's rating change for a single set vs a single opponent, via the real v2 calculator. */
    private fun singleSetChange(
        subject: Double,
        opponent: Double,
        subjectGames: Int,
        opponentGames: Int,
    ): Double {
        val subjectWon = subjectGames > opponentGames
        val request =
            RankingCalculationRequest(
                teams =
                    mapOf(
                        "S" to virtualTeam(teamId = "S", rating = subject),
                        "O" to virtualTeam(teamId = "O", rating = opponent),
                    ),
                matchScore =
                    MatchScore(
                        sets =
                            listOf(
                                element =
                                    SetScore(
                                        games = mapOf("S" to subjectGames, "O" to opponentGames),
                                        winnerTeamId = if (subjectWon) "S" else "O",
                                    ),
                            ),
                    ),
                options = null,
            )
        return calculator.calculate(request = request).response.ratingChanges.getValue(key = "S").change.toDouble()
    }

    private fun virtualTeam(
        teamId: String,
        rating: Double,
    ): Team =
        Team(
            teamId = teamId,
            name = teamId,
            players = listOf(element = PlayerProfile(playerId = teamId, name = teamId, rating = ratingOf(value = rating))),
            teamType = TeamType.SINGLES,
        )

    private fun ratingOf(value: Double): Rating {
        val clamped = value.coerceIn(minimumValue = 1.0, maximumValue = 7.0).toBigDecimal().setScale(6, RoundingMode.HALF_UP)
        return Rating.fromValue(value = clamped.toPlainString())
    }

    private fun clampRating(value: Double): Double = value.coerceIn(minimumValue = 1.0, maximumValue = 7.0)

    // --- the two schemes (each returns the four players' deltas for a set: [a1, a2, b1, b2]) ---

    /** Scheme 1: each player vs the opponents' mean, using their own rating as the subject. */
    private fun scheme1(
        r: DoubleArray,
        a1: Int,
        a2: Int,
        b1: Int,
        b2: Int,
        gamesA: Int,
        gamesB: Int,
    ): DoubleArray {
        val meanA = (r[a1] + r[a2]) / 2.0
        val meanB = (r[b1] + r[b2]) / 2.0
        return doubleArrayOf(
            singleSetChange(subject = r[a1], opponent = meanB, subjectGames = gamesA, opponentGames = gamesB),
            singleSetChange(subject = r[a2], opponent = meanB, subjectGames = gamesA, opponentGames = gamesB),
            singleSetChange(subject = r[b1], opponent = meanA, subjectGames = gamesB, opponentGames = gamesA),
            singleSetChange(subject = r[b2], opponent = meanA, subjectGames = gamesB, opponentGames = gamesA),
        )
    }

    /**
     * Scheme 2: one team change from the team [aggregate] vs the opponents' aggregate, split within the
     * team so the aggregate moves by the full team delta (δ = Δ_team · rᵢ / aggregate). Mean keeps the
     * team rating on 1–7; sum is the off-scale variant (the engine clamps it into 1–7).
     */
    private fun scheme2(
        r: DoubleArray,
        a1: Int,
        a2: Int,
        b1: Int,
        b2: Int,
        gamesA: Int,
        gamesB: Int,
        aggregate: (Double, Double) -> Double,
    ): DoubleArray {
        val aggA = aggregate(r[a1], r[a2])
        val aggB = aggregate(r[b1], r[b2])
        val teamDeltaA = singleSetChange(subject = aggA, opponent = aggB, subjectGames = gamesA, opponentGames = gamesB)
        val teamDeltaB = singleSetChange(subject = aggB, opponent = aggA, subjectGames = gamesB, opponentGames = gamesA)
        return doubleArrayOf(
            teamDeltaA * r[a1] / aggA,
            teamDeltaA * r[a2] / aggA,
            teamDeltaB * r[b1] / aggB,
            teamDeltaB * r[b2] / aggB,
        )
    }

    /** Outcome model: winner + game margin driven only by the two teams' true strength (team = mean θ). */
    private fun playSet(
        strengthA: Double,
        strengthB: Double,
        random: Random,
    ): Pair<Int, Int> {
        val gap = strengthA - strengthB
        val probA = 1.0 / (1.0 + exp(-WIN_STEEPNESS * gap))
        val aWins = random.nextDouble() < probA
        val expectedLoser = 5.0 - 3.0 * (abs(gap).coerceAtMost(maximumValue = 1.5) / 1.5)
        val loserGames = (expectedLoser.roundToInt() + random.nextInt(from = -1, until = 2)).coerceIn(minimumValue = 0, maximumValue = 5)
        return if (aWins) 6 to loserGames else loserGames to 6
    }

    @Test
    fun `doubles scheme fairness — monte carlo`() {
        val seeds = listOf(SEED, 11L, 22L, 33L, 44L)
        val perSeed = seeds.map { simulate(seed = it) } // each → [Scheme1, Scheme2-mean, Scheme2-sum]
        val s1 = averageOf(runs = perSeed.map { it[0] })
        val s2m = averageOf(runs = perSeed.map { it[1] })
        val s2s = averageOf(runs = perSeed.map { it[2] })

        fun row(
            label: String,
            s: Stats,
        ) = "$label | RMSE=${f(s.rmse)} | RMSE(centered)=${f(s.rmseCentered)} | Pearson=${f(s.pearson)} | " +
            "drift(mean)=${f(s.drift)} | within-band σ=${f(s.withinBandSd)}"

        println(
            buildString {
                appendLine("=== Doubles rating scheme Monte-Carlo (${seeds.size} seeds, $PLAYERS players x $ROUNDS rounds, averaged) ===")
                appendLine("true skill ~ N(${f(SKILL_MEAN, 2)}, ${f(SKILL_SD, 2)}) clamped [$SKILL_MIN, $SKILL_MAX]")
                appendLine("init: all players @ the true population mean")
                appendLine(row(label = "Scheme 1        ", s = s1))
                appendLine(row(label = "Scheme 2 (mean) ", s = s2m))
                appendLine(row(label = "Scheme 2 (sum)  ", s = s2s))
                append(upsetIllustration())
            },
        )

        // Harness sanity + robust properties (not the final scheme pick):
        s1.pearson shouldBeGreaterThan 0.7 // scheme 1 recovers skill
        s2m.pearson shouldBeGreaterThan 0.7 // scheme 2 (mean) recovers skill
        abs(s2m.drift) shouldBeLessThan 0.02 // scheme 2 (mean) conserves total rating
    }

    private fun averageOf(runs: List<Stats>): Stats =
        Stats(
            rmse = runs.map { it.rmse }.average(),
            rmseCentered = runs.map { it.rmseCentered }.average(),
            pearson = runs.map { it.pearson }.average(),
            drift = runs.map { it.drift }.average(),
            withinBandSd = runs.map { it.withinBandSd }.average(),
        )

    /** One full simulation for a seed; returns [Scheme 1, Scheme 2 (mean), Scheme 2 (sum)] stats. */
    private fun simulate(seed: Long): List<Stats> {
        val random = Random(seed = seed)
        val theta = DoubleArray(PLAYERS) { gaussian(random = random, mean = SKILL_MEAN, sd = SKILL_SD).coerceIn(SKILL_MIN, SKILL_MAX) }
        val initMean = theta.average()

        // Each scheme starts from the same neutral prior (everyone at the true population mean) so that
        // conservation is not unfairly penalised, and processes the identical stream of matches.
        val r1 = DoubleArray(PLAYERS) { initMean }
        val r2mean = DoubleArray(PLAYERS) { initMean }
        val r2sum = DoubleArray(PLAYERS) { initMean }

        repeat(times = ROUNDS) {
            val order = (0 until PLAYERS).shuffled(random = random)
            var i = 0
            while (i + 3 < PLAYERS) {
                val a1 = order[i]
                val a2 = order[i + 1]
                val b1 = order[i + 2]
                val b2 = order[i + 3]
                i += 4
                val (gamesA, gamesB) =
                    playSet(strengthA = (theta[a1] + theta[a2]) / 2.0, strengthB = (theta[b1] + theta[b2]) / 2.0, random = random)

                apply(r = r1, d = scheme1(r = r1, a1 = a1, a2 = a2, b1 = b1, b2 = b2, gamesA = gamesA, gamesB = gamesB), a1, a2, b1, b2)
                apply(
                    r = r2mean,
                    d = scheme2(r = r2mean, a1 = a1, a2 = a2, b1 = b1, b2 = b2, gamesA = gamesA, gamesB = gamesB) { x, y -> (x + y) / 2.0 },
                    a1,
                    a2,
                    b1,
                    b2,
                )
                apply(
                    r = r2sum,
                    d = scheme2(r = r2sum, a1 = a1, a2 = a2, b1 = b1, b2 = b2, gamesA = gamesA, gamesB = gamesB) { x, y -> x + y },
                    a1,
                    a2,
                    b1,
                    b2,
                )
            }
        }

        return listOf(
            stats(rating = r1, theta = theta, initMean = initMean),
            stats(rating = r2mean, theta = theta, initMean = initMean),
            stats(rating = r2sum, theta = theta, initMean = initMean),
        )
    }

    private fun f(
        x: Double,
        dp: Int = 4,
    ): String = x.toBigDecimal().setScale(dp, RoundingMode.HALF_UP).toPlainString()

    /** A concrete unbalanced-team win: shows the opposite within-team splits of the two schemes. */
    private fun upsetIllustration(): String {
        val r = doubleArrayOf(5.0, 3.0, 4.0, 4.0) // A = {5.0, 3.0} (mean 4.0) beats even B = {4.0, 4.0}, 6-2
        val d1 = scheme1(r = r, a1 = 0, a2 = 1, b1 = 2, b2 = 3, gamesA = 6, gamesB = 2)
        val d2 = scheme2(r = r, a1 = 0, a2 = 1, b1 = 2, b2 = 3, gamesA = 6, gamesB = 2) { x, y -> (x + y) / 2.0 }
        return buildString {
            appendLine()
            appendLine("--- Illustration: A={5.0, 3.0} beats B={4.0, 4.0} 6-2 (who gains more?) ---")
            appendLine("Scheme 1:        strong(5.0) Δ=${f(d1[0])}   weak(3.0) Δ=${f(d1[1])}")
            appendLine("Scheme 2 (mean): strong(5.0) Δ=${f(d2[0])}   weak(3.0) Δ=${f(d2[1])}")
        }
    }

    private fun apply(
        r: DoubleArray,
        d: DoubleArray,
        a1: Int,
        a2: Int,
        b1: Int,
        b2: Int,
    ) {
        r[a1] = clampRating(value = r[a1] + d[0])
        r[a2] = clampRating(value = r[a2] + d[1])
        r[b1] = clampRating(value = r[b1] + d[2])
        r[b2] = clampRating(value = r[b2] + d[3])
    }

    private class Stats(
        val rmse: Double,
        val rmseCentered: Double,
        val pearson: Double,
        val drift: Double,
        val withinBandSd: Double,
    )

    private fun stats(
        rating: DoubleArray,
        theta: DoubleArray,
        initMean: Double,
    ): Stats {
        val n = rating.size
        val rmse = sqrt((0 until n).sumOf { (rating[it] - theta[it]) * (rating[it] - theta[it]) } / n)
        val rMean = rating.average()
        val tMean = theta.average()
        val rmseCentered = sqrt((0 until n).sumOf { ((rating[it] - rMean) - (theta[it] - tMean)).let { e -> e * e } } / n)
        val cov = (0 until n).sumOf { (rating[it] - rMean) * (theta[it] - tMean) }
        val vr = sqrt((0 until n).sumOf { (rating[it] - rMean) * (rating[it] - rMean) })
        val vt = sqrt((0 until n).sumOf { (theta[it] - tMean) * (theta[it] - tMean) })
        val pearson = cov / (vr * vt)
        // Within-band consistency: for each 0.5-wide true-skill band, the spread of ratings (partner-independence).
        val byBand = (0 until n).groupBy { (theta[it] * 2).roundToInt() }
        val withinBandSd =
            byBand.values.filter { it.size > 1 }.map { idx ->
                val m = idx.map { rating[it] }.average()
                sqrt(idx.sumOf { (rating[it] - m) * (rating[it] - m) } / idx.size)
            }.average()
        return Stats(rmse = rmse, rmseCentered = rmseCentered, pearson = pearson, drift = rMean - initMean, withinBandSd = withinBandSd)
    }

    private fun gaussian(
        random: Random,
        mean: Double,
        sd: Double,
    ): Double {
        // Box–Muller.
        val u1 = random.nextDouble().coerceAtLeast(minimumValue = 1e-12)
        val u2 = random.nextDouble()
        val z = sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
        return mean + sd * z
    }
}
