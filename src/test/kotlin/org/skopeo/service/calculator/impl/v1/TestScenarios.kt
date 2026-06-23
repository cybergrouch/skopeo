// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.calculator.impl.v1

import org.skopeo.service.calculator.impl.bd
import org.skopeo.service.calculator.impl.divideBy
import org.skopeo.service.calculator.impl.toStringPrecise
import java.math.BigDecimal

/**
 * Shared NTRP rating-calculation scenarios used by PerformanceBasedRankingCalculatorImplTest.
 */
data class RatingScenario(
    val id: String,
    val description: String,
    val ntrpP1: String,
    val ntrpP2: String,
    val p1Games: Int,
    val p2Games: Int,
    val winner: String,
    val expectedNtrpP1Delta: String,
    val expectedNtrpP2Delta: String,
    val smoothingEnabled: Boolean = false,
    val smoothingFactor: Double = 0.5,
)

object TestScenarios {
    /**
     * All NTRP published levels in 0.5 steps: 1.0, 1.5, ..., 7.0 (13 levels).
     * Crossing this list with itself yields every possible NTRP matchup.
     */
    val allNtrpLevels: List<String> =
        (0..12).map { step ->
            val tenths = 10 + step * 5
            "${tenths / 10}.${tenths % 10}"
        }

    /**
     * All legal single-set scores from the winner's perspective
     * (7-6 implies a tiebreak, attached automatically by createSinglesRequest).
     */
    val allSingleSetScores: List<Pair<Int, Int>> =
        listOf(6 to 0, 6 to 1, 6 to 2, 6 to 3, 6 to 4, 7 to 5, 7 to 6)

    /**
     * Curated, hand-pinned scenarios (S: levels/gaps, SM: smoothing, GS: score sweep).
     * Their expected deltas are literal regression values; the broader generated
     * sweeps (PL/CG/EX) are built further down and combined in [allScenarios].
     *
     * NTRP uses a 0.5 competitive threshold (8.3% of the 6.0 range).
     */
    private val curatedScenarios =
        listOf(
            // NTRP 2.5 level (UTR 5.0 level)
            RatingScenario(
                id = "S1",
                description = "Low: Equal players, dominant (6-0)",
                ntrpP1 = "2.5",
                ntrpP2 = "2.5",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.160000",
                expectedNtrpP2Delta = "-0.160000",
            ),
            RatingScenario(
                id = "S2",
                description = "Low: Equal players, close (6-4)",
                ntrpP1 = "2.5",
                ntrpP2 = "2.5",
                p1Games = 6,
                p2Games = 4,
                winner = "T1",
                expectedNtrpP1Delta = "0.032000",
                expectedNtrpP2Delta = "-0.032000",
            ),
            RatingScenario(
                id = "S3",
                description = "Low: Below threshold, 0.1 gap (6-0)",
                ntrpP1 = "2.6",
                ntrpP2 = "2.5",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.127871",
                expectedNtrpP2Delta = "-0.127871",
            ),
            RatingScenario(
                id = "S4",
                description = "Low: 0.5 gap, expected win (6-0)",
                ntrpP1 = "3.0",
                ntrpP2 = "2.5",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.000000",
                expectedNtrpP2Delta = "0.000000",
            ),
            RatingScenario(
                id = "S5",
                description = "Low: 0.5 gap, expected win (6-4)",
                ntrpP1 = "3.0",
                ntrpP2 = "2.5",
                p1Games = 6,
                p2Games = 4,
                winner = "T1",
                expectedNtrpP1Delta = "0.000000",
                expectedNtrpP2Delta = "0.000000",
            ),
            RatingScenario(
                id = "S6",
                description = "Low: Below threshold, 0.1 gap (6-0)",
                ntrpP1 = "3.1",
                ntrpP2 = "3.0",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.127871",
                expectedNtrpP2Delta = "-0.127871",
            ),
            RatingScenario(
                id = "S7",
                description = "Low: 1.0 gap, expected win (6-0)",
                ntrpP1 = "3.5",
                ntrpP2 = "2.5",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.000000",
                expectedNtrpP2Delta = "0.000000",
            ),
            RatingScenario(
                id = "S8",
                description = "Low: 1.0 gap, upset (6-0)",
                ntrpP1 = "2.5",
                ntrpP2 = "3.5",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.642572",
                expectedNtrpP2Delta = "-0.642572",
            ),
            RatingScenario(
                id = "S9",
                description = "Low: Below threshold, 0.1 gap (6-0)",
                ntrpP1 = "3.6",
                ntrpP2 = "3.5",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.127871",
                expectedNtrpP2Delta = "-0.127871",
            ),
            RatingScenario(
                id = "S10",
                description = "Low: 1.5 gap, expected win (6-0)",
                ntrpP1 = "4.0",
                ntrpP2 = "2.5",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.000000",
                expectedNtrpP2Delta = "0.000000",
            ),
            RatingScenario(
                id = "S11",
                description = "Low: Below threshold, 0.1 gap (6-0)",
                ntrpP1 = "4.1",
                ntrpP2 = "4.0",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.127871",
                expectedNtrpP2Delta = "-0.127871",
            ),
            // NTRP 4.0-4.5 level (UTR 8.75-10.0 level)
            RatingScenario(
                id = "S12",
                description = "Mid: 2.0 gap, big upset (6-0)",
                ntrpP1 = "2.5",
                ntrpP2 = "4.5",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "1.285139",
                expectedNtrpP2Delta = "-1.285139",
            ),
            RatingScenario(
                id = "S13",
                description = "Mid: Expected win, dominant (6-0)",
                ntrpP1 = "4.5",
                ntrpP2 = "4.0",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.000000",
                expectedNtrpP2Delta = "0.000000",
            ),
            RatingScenario(
                id = "S14",
                description = "Mid: Expected win, close (6-4)",
                ntrpP1 = "4.5",
                ntrpP2 = "4.0",
                p1Games = 6,
                p2Games = 4,
                winner = "T1",
                expectedNtrpP1Delta = "0.000000",
                expectedNtrpP2Delta = "0.000000",
            ),
            RatingScenario(
                id = "S15",
                description = "Mid: Upset, dominant (6-0)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.5",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.321284",
                expectedNtrpP2Delta = "-0.321284",
            ),
            RatingScenario(
                id = "S16",
                description = "Mid: Upset, close (6-4)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.5",
                p1Games = 6,
                p2Games = 4,
                winner = "T1",
                expectedNtrpP1Delta = "0.064257",
                expectedNtrpP2Delta = "-0.064257",
            ),
            RatingScenario(
                id = "S17",
                description = "Mid: Competitive, dominant (6-0)",
                ntrpP1 = "4.5",
                ntrpP2 = "4.3",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.095744",
                expectedNtrpP2Delta = "-0.095744",
            ),
            RatingScenario(
                id = "S18",
                description = "Mid: Competitive, close (6-4)",
                ntrpP1 = "4.5",
                ntrpP2 = "4.3",
                p1Games = 6,
                p2Games = 4,
                winner = "T1",
                expectedNtrpP1Delta = "0.019149",
                expectedNtrpP2Delta = "-0.019149",
            ),
            RatingScenario(
                id = "S19",
                description = "Mid: Equal players, dominant (6-0)",
                ntrpP1 = "4.5",
                ntrpP2 = "4.5",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.160000",
                expectedNtrpP2Delta = "-0.160000",
            ),
            RatingScenario(
                id = "S20",
                description = "Mid: Equal players, close (6-4)",
                ntrpP1 = "4.5",
                ntrpP2 = "4.5",
                p1Games = 6,
                p2Games = 4,
                winner = "T1",
                expectedNtrpP1Delta = "0.032000",
                expectedNtrpP2Delta = "-0.032000",
            ),
            RatingScenario(
                id = "S21",
                description = "Mid: Below threshold, 0.1 gap (6-0)",
                ntrpP1 = "4.6",
                ntrpP2 = "4.5",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.127871",
                expectedNtrpP2Delta = "-0.127871",
            ),
            // NTRP 4.0-5.0 level (UTR 10.0-12.5 level) - Large gaps
            RatingScenario(
                id = "S22",
                description = "High: Large gap, expected win (6-0)",
                ntrpP1 = "5.0",
                ntrpP2 = "4.0",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.000000",
                expectedNtrpP2Delta = "0.000000",
            ),
            RatingScenario(
                id = "S23",
                description = "High: Large gap, big upset (6-0)",
                ntrpP1 = "4.0",
                ntrpP2 = "5.0",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.642572",
                expectedNtrpP2Delta = "-0.642572",
            ),
            RatingScenario(
                id = "S24",
                description = "High: Below threshold, 0.1 gap (6-0)",
                ntrpP1 = "5.1",
                ntrpP2 = "5.0",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.127871",
                expectedNtrpP2Delta = "-0.127871",
            ),
            // Smoothing scenarios - Equal players with different smoothing factors
            RatingScenario(
                id = "SM1",
                description = "Smoothing 0.3: Equal, dominant (6-0)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.0",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.048000",
                expectedNtrpP2Delta = "-0.048000",
                smoothingEnabled = true,
                smoothingFactor = 0.3,
            ),
            RatingScenario(
                id = "SM2",
                description = "Smoothing 0.5: Equal, dominant (6-0)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.0",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.080000",
                expectedNtrpP2Delta = "-0.080000",
                smoothingEnabled = true,
                smoothingFactor = 0.5,
            ),
            RatingScenario(
                id = "SM3",
                description = "Smoothing 0.7: Equal, dominant (6-0)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.0",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.112000",
                expectedNtrpP2Delta = "-0.112000",
                smoothingEnabled = true,
                smoothingFactor = 0.7,
            ),
            RatingScenario(
                id = "SM4",
                description = "Smoothing 0.3: Equal, close (6-4)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.0",
                p1Games = 6,
                p2Games = 4,
                winner = "T1",
                expectedNtrpP1Delta = "0.009600",
                expectedNtrpP2Delta = "-0.009600",
                smoothingEnabled = true,
                smoothingFactor = 0.3,
            ),
            RatingScenario(
                id = "SM5",
                description = "Smoothing 0.5: Equal, close (6-4)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.0",
                p1Games = 6,
                p2Games = 4,
                winner = "T1",
                expectedNtrpP1Delta = "0.016000",
                expectedNtrpP2Delta = "-0.016000",
                smoothingEnabled = true,
                smoothingFactor = 0.5,
            ),
            RatingScenario(
                id = "SM6",
                description = "Smoothing 0.5: Upset (6-0)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.5",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.160642",
                expectedNtrpP2Delta = "-0.160642",
                smoothingEnabled = true,
                smoothingFactor = 0.5,
            ),
            // Non-competitive smoothing scenarios: Expected wins (favorite beats underdog)
            RatingScenario(
                id = "SM7",
                description = "Smoothing 0.5: Expected win at threshold",
                ntrpP1 = "4.5",
                ntrpP2 = "4.0",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.000000",
                expectedNtrpP2Delta = "0.000000",
                smoothingEnabled = true,
                smoothingFactor = 0.5,
            ),
            RatingScenario(
                id = "SM8",
                description = "Smoothing 0.5: Competitive gap, dominant (6-0)",
                ntrpP1 = "4.5",
                ntrpP2 = "4.3",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.047872",
                expectedNtrpP2Delta = "-0.047872",
                smoothingEnabled = true,
                smoothingFactor = 0.5,
            ),
            RatingScenario(
                id = "SM9",
                description = "Smoothing 0.3: Competitive gap, close (6-4)",
                ntrpP1 = "4.5",
                ntrpP2 = "4.3",
                p1Games = 6,
                p2Games = 4,
                winner = "T1",
                expectedNtrpP1Delta = "0.005745",
                expectedNtrpP2Delta = "-0.005745",
                smoothingEnabled = true,
                smoothingFactor = 0.3,
            ),
            // Non-competitive smoothing scenarios: Upsets with different factors
            RatingScenario(
                id = "SM10",
                description = "Smoothing 0.3: Small upset (6-4)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.5",
                p1Games = 6,
                p2Games = 4,
                winner = "T1",
                expectedNtrpP1Delta = "0.019277",
                expectedNtrpP2Delta = "-0.019277",
                smoothingEnabled = true,
                smoothingFactor = 0.3,
            ),
            RatingScenario(
                id = "SM11",
                description = "Smoothing 0.7: Medium upset (6-0)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.5",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.224899",
                expectedNtrpP2Delta = "-0.224899",
                smoothingEnabled = true,
                smoothingFactor = 0.7,
            ),
            RatingScenario(
                id = "SM12",
                description = "Smoothing 0.5: Large upset (1.0 gap)",
                ntrpP1 = "2.5",
                ntrpP2 = "3.5",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.321286",
                expectedNtrpP2Delta = "-0.321286",
                smoothingEnabled = true,
                smoothingFactor = 0.5,
            ),
            RatingScenario(
                id = "SM13",
                description = "Smoothing 0.3: Huge upset (2.0 gap)",
                ntrpP1 = "2.5",
                ntrpP2 = "4.5",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.385542",
                expectedNtrpP2Delta = "-0.385542",
                smoothingEnabled = true,
                smoothingFactor = 0.3,
            ),
            // Non-competitive smoothing scenarios: Below threshold gaps
            RatingScenario(
                id = "SM14",
                description = "Smoothing 0.5: Small gap (0.1), dominant",
                ntrpP1 = "4.1",
                ntrpP2 = "4.0",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.063935",
                expectedNtrpP2Delta = "-0.063935",
                smoothingEnabled = true,
                smoothingFactor = 0.5,
            ),
            RatingScenario(
                id = "SM15",
                description = "Smoothing 0.7: Small gap (0.1), close",
                ntrpP1 = "4.6",
                ntrpP2 = "4.5",
                p1Games = 6,
                p2Games = 4,
                winner = "T1",
                expectedNtrpP1Delta = "0.017902",
                expectedNtrpP2Delta = "-0.017902",
                smoothingEnabled = true,
                smoothingFactor = 0.7,
            ),
            // Non-competitive smoothing scenarios: Boundary conditions
            RatingScenario(
                id = "SM16",
                description = "Smoothing 0.5: High rating upset",
                ntrpP1 = "4.0",
                ntrpP2 = "5.0",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.321286",
                expectedNtrpP2Delta = "-0.321286",
                smoothingEnabled = true,
                smoothingFactor = 0.5,
            ),
            RatingScenario(
                id = "SM17",
                description = "Smoothing 0.3: Low rating levels",
                ntrpP1 = "2.6",
                ntrpP2 = "2.5",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.038361",
                expectedNtrpP2Delta = "-0.038361",
                smoothingEnabled = true,
                smoothingFactor = 0.3,
            ),
            RatingScenario(
                id = "SM18",
                description = "Smoothing 0.7: High rating levels",
                ntrpP1 = "5.1",
                ntrpP2 = "5.0",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.089510",
                expectedNtrpP2Delta = "-0.089510",
                smoothingEnabled = true,
                smoothingFactor = 0.7,
            ),
            // Game-score sweep: every legal set score per representative rating difference
            RatingScenario(
                id = "GS1",
                description = "Scores: Equal players (6-0)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.0",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.160000",
                expectedNtrpP2Delta = "-0.160000",
            ),
            RatingScenario(
                id = "GS2",
                description = "Scores: Equal players (6-1)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.0",
                p1Games = 6,
                p2Games = 1,
                winner = "T1",
                expectedNtrpP1Delta = "0.114286",
                expectedNtrpP2Delta = "-0.114286",
            ),
            RatingScenario(
                id = "GS3",
                description = "Scores: Equal players (6-2)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.0",
                p1Games = 6,
                p2Games = 2,
                winner = "T1",
                expectedNtrpP1Delta = "0.080000",
                expectedNtrpP2Delta = "-0.080000",
            ),
            RatingScenario(
                id = "GS4",
                description = "Scores: Equal players (6-3)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.0",
                p1Games = 6,
                p2Games = 3,
                winner = "T1",
                expectedNtrpP1Delta = "0.053333",
                expectedNtrpP2Delta = "-0.053333",
            ),
            RatingScenario(
                id = "GS5",
                description = "Scores: Equal players (6-4)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.0",
                p1Games = 6,
                p2Games = 4,
                winner = "T1",
                expectedNtrpP1Delta = "0.032000",
                expectedNtrpP2Delta = "-0.032000",
            ),
            RatingScenario(
                id = "GS6",
                description = "Scores: Equal players (7-5)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.0",
                p1Games = 7,
                p2Games = 5,
                winner = "T1",
                expectedNtrpP1Delta = "0.026667",
                expectedNtrpP2Delta = "-0.026667",
            ),
            RatingScenario(
                id = "GS7",
                description = "Scores: Equal players (7-6)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.0",
                p1Games = 7,
                p2Games = 6,
                winner = "T1",
                expectedNtrpP1Delta = "0.012308",
                expectedNtrpP2Delta = "-0.012308",
            ),
            RatingScenario(
                id = "GS8",
                description = "Scores: 0.25 gap, expected win (6-0)",
                ntrpP1 = "4.25",
                ntrpP2 = "4.0",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.079678",
                expectedNtrpP2Delta = "-0.079678",
            ),
            RatingScenario(
                id = "GS9",
                description = "Scores: 0.25 gap, expected win (6-1)",
                ntrpP1 = "4.25",
                ntrpP2 = "4.0",
                p1Games = 6,
                p2Games = 1,
                winner = "T1",
                expectedNtrpP1Delta = "0.056913",
                expectedNtrpP2Delta = "-0.056913",
            ),
            RatingScenario(
                id = "GS10",
                description = "Scores: 0.25 gap, expected win (6-2)",
                ntrpP1 = "4.25",
                ntrpP2 = "4.0",
                p1Games = 6,
                p2Games = 2,
                winner = "T1",
                expectedNtrpP1Delta = "0.039839",
                expectedNtrpP2Delta = "-0.039839",
            ),
            RatingScenario(
                id = "GS11",
                description = "Scores: 0.25 gap, expected win (6-3)",
                ntrpP1 = "4.25",
                ntrpP2 = "4.0",
                p1Games = 6,
                p2Games = 3,
                winner = "T1",
                expectedNtrpP1Delta = "0.026559",
                expectedNtrpP2Delta = "-0.026559",
            ),
            RatingScenario(
                id = "GS12",
                description = "Scores: 0.25 gap, expected win (6-4)",
                ntrpP1 = "4.25",
                ntrpP2 = "4.0",
                p1Games = 6,
                p2Games = 4,
                winner = "T1",
                expectedNtrpP1Delta = "0.015936",
                expectedNtrpP2Delta = "-0.015936",
            ),
            RatingScenario(
                id = "GS13",
                description = "Scores: 0.25 gap, expected win (7-5)",
                ntrpP1 = "4.25",
                ntrpP2 = "4.0",
                p1Games = 7,
                p2Games = 5,
                winner = "T1",
                expectedNtrpP1Delta = "0.013280",
                expectedNtrpP2Delta = "-0.013280",
            ),
            RatingScenario(
                id = "GS14",
                description = "Scores: 0.25 gap, expected win (7-6)",
                ntrpP1 = "4.25",
                ntrpP2 = "4.0",
                p1Games = 7,
                p2Games = 6,
                winner = "T1",
                expectedNtrpP1Delta = "0.006129",
                expectedNtrpP2Delta = "-0.006129",
            ),
            RatingScenario(
                id = "GS15",
                description = "Scores: 0.25 gap, upset (6-0)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.25",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.160644",
                expectedNtrpP2Delta = "-0.160644",
            ),
            RatingScenario(
                id = "GS16",
                description = "Scores: 0.25 gap, upset (6-1)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.25",
                p1Games = 6,
                p2Games = 1,
                winner = "T1",
                expectedNtrpP1Delta = "0.114746",
                expectedNtrpP2Delta = "-0.114746",
            ),
            RatingScenario(
                id = "GS17",
                description = "Scores: 0.25 gap, upset (6-2)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.25",
                p1Games = 6,
                p2Games = 2,
                winner = "T1",
                expectedNtrpP1Delta = "0.080322",
                expectedNtrpP2Delta = "-0.080322",
            ),
            RatingScenario(
                id = "GS18",
                description = "Scores: 0.25 gap, upset (6-3)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.25",
                p1Games = 6,
                p2Games = 3,
                winner = "T1",
                expectedNtrpP1Delta = "0.053548",
                expectedNtrpP2Delta = "-0.053548",
            ),
            RatingScenario(
                id = "GS19",
                description = "Scores: 0.25 gap, upset (6-4)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.25",
                p1Games = 6,
                p2Games = 4,
                winner = "T1",
                expectedNtrpP1Delta = "0.032129",
                expectedNtrpP2Delta = "-0.032129",
            ),
            RatingScenario(
                id = "GS20",
                description = "Scores: 0.25 gap, upset (7-5)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.25",
                p1Games = 7,
                p2Games = 5,
                winner = "T1",
                expectedNtrpP1Delta = "0.026774",
                expectedNtrpP2Delta = "-0.026774",
            ),
            RatingScenario(
                id = "GS21",
                description = "Scores: 0.25 gap, upset (7-6)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.25",
                p1Games = 7,
                p2Games = 6,
                winner = "T1",
                expectedNtrpP1Delta = "0.012357",
                expectedNtrpP2Delta = "-0.012357",
            ),
            RatingScenario(
                id = "GS22",
                description = "Scores: 0.5 gap, upset (6-0)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.5",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.321284",
                expectedNtrpP2Delta = "-0.321284",
            ),
            RatingScenario(
                id = "GS23",
                description = "Scores: 0.5 gap, upset (6-1)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.5",
                p1Games = 6,
                p2Games = 1,
                winner = "T1",
                expectedNtrpP1Delta = "0.229489",
                expectedNtrpP2Delta = "-0.229489",
            ),
            RatingScenario(
                id = "GS24",
                description = "Scores: 0.5 gap, upset (6-2)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.5",
                p1Games = 6,
                p2Games = 2,
                winner = "T1",
                expectedNtrpP1Delta = "0.160642",
                expectedNtrpP2Delta = "-0.160642",
            ),
            RatingScenario(
                id = "GS25",
                description = "Scores: 0.5 gap, upset (6-3)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.5",
                p1Games = 6,
                p2Games = 3,
                winner = "T1",
                expectedNtrpP1Delta = "0.107095",
                expectedNtrpP2Delta = "-0.107095",
            ),
            RatingScenario(
                id = "GS26",
                description = "Scores: 0.5 gap, upset (6-4)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.5",
                p1Games = 6,
                p2Games = 4,
                winner = "T1",
                expectedNtrpP1Delta = "0.064257",
                expectedNtrpP2Delta = "-0.064257",
            ),
            RatingScenario(
                id = "GS27",
                description = "Scores: 0.5 gap, upset (7-5)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.5",
                p1Games = 7,
                p2Games = 5,
                winner = "T1",
                expectedNtrpP1Delta = "0.053547",
                expectedNtrpP2Delta = "-0.053547",
            ),
            RatingScenario(
                id = "GS28",
                description = "Scores: 0.5 gap, upset (7-6)",
                ntrpP1 = "4.0",
                ntrpP2 = "4.5",
                p1Games = 7,
                p2Games = 6,
                winner = "T1",
                expectedNtrpP1Delta = "0.024714",
                expectedNtrpP2Delta = "-0.024714",
            ),
            RatingScenario(
                id = "GS29",
                description = "Scores: 1.0 gap, upset (6-0)",
                ntrpP1 = "4.0",
                ntrpP2 = "5.0",
                p1Games = 6,
                p2Games = 0,
                winner = "T1",
                expectedNtrpP1Delta = "0.642572",
                expectedNtrpP2Delta = "-0.642572",
            ),
            RatingScenario(
                id = "GS30",
                description = "Scores: 1.0 gap, upset (6-1)",
                ntrpP1 = "4.0",
                ntrpP2 = "5.0",
                p1Games = 6,
                p2Games = 1,
                winner = "T1",
                expectedNtrpP1Delta = "0.458980",
                expectedNtrpP2Delta = "-0.458980",
            ),
            RatingScenario(
                id = "GS31",
                description = "Scores: 1.0 gap, upset (6-2)",
                ntrpP1 = "4.0",
                ntrpP2 = "5.0",
                p1Games = 6,
                p2Games = 2,
                winner = "T1",
                expectedNtrpP1Delta = "0.321286",
                expectedNtrpP2Delta = "-0.321286",
            ),
            RatingScenario(
                id = "GS32",
                description = "Scores: 1.0 gap, upset (6-3)",
                ntrpP1 = "4.0",
                ntrpP2 = "5.0",
                p1Games = 6,
                p2Games = 3,
                winner = "T1",
                expectedNtrpP1Delta = "0.214190",
                expectedNtrpP2Delta = "-0.214190",
            ),
            RatingScenario(
                id = "GS33",
                description = "Scores: 1.0 gap, upset (6-4)",
                ntrpP1 = "4.0",
                ntrpP2 = "5.0",
                p1Games = 6,
                p2Games = 4,
                winner = "T1",
                expectedNtrpP1Delta = "0.128514",
                expectedNtrpP2Delta = "-0.128514",
            ),
            RatingScenario(
                id = "GS34",
                description = "Scores: 1.0 gap, upset (7-5)",
                ntrpP1 = "4.0",
                ntrpP2 = "5.0",
                p1Games = 7,
                p2Games = 5,
                winner = "T1",
                expectedNtrpP1Delta = "0.107095",
                expectedNtrpP2Delta = "-0.107095",
            ),
            RatingScenario(
                id = "GS35",
                description = "Scores: 1.0 gap, upset (7-6)",
                ntrpP1 = "4.0",
                ntrpP2 = "5.0",
                p1Games = 7,
                p2Games = 6,
                winner = "T1",
                expectedNtrpP1Delta = "0.049429",
                expectedNtrpP2Delta = "-0.049429",
            ),
        )

    /**
     * Published competition levels: 2.5–7.0 in 0.5 steps (10 levels).
     */
    val publishedCompetitionLevels: List<String> = allNtrpLevels.filter { level -> level.toDouble() >= 2.5 }

    /**
     * Score sweep for the generated groups: 6-0 through 7-5 (7-6 is exercised separately).
     */
    private val sweepScores: List<Pair<Int, Int>> = allSingleSetScores.filter { score -> score != 7 to 6 }

    private data class SystemConstants(
        val kFactor: BigDecimal,
        val ratingRange: BigDecimal,
        val minRating: BigDecimal,
        val maxRating: BigDecimal,
    )

    private val ntrpConstants =
        SystemConstants(
            kFactor = "0.16".bd,
            ratingRange = "6.0".bd,
            minRating = "1.0".bd,
            maxRating = "7.0".bd,
        )

    /**
     * Every matchup of the published competition levels (2.5–7.0, all 100 ordered pairs:
     * expected wins, upsets, and equal pairings) at every score from 6-0 to 7-5.
     */
    val publishedLevelScenarios: List<RatingScenario> =
        buildList {
            var index = 1
            publishedCompetitionLevels.forEach { p1 ->
                publishedCompetitionLevels.forEach { p2 ->
                    sweepScores.forEach { score ->
                        add(generatedScenario(id = "PL$index", group = "Published", ntrpP1 = p1, ntrpP2 = p2, score = score))
                        index++
                    }
                }
            }
        }

    /**
     * Competitive matchups between 3.0 and 4.5 with a 0.25 gap (below the 0.5 threshold),
     * in both directions, at every score from 6-0 to 7-5.
     */
    val competitiveGapScenarios: List<RatingScenario> =
        buildList {
            val pairs =
                listOf(
                    "3.0" to "3.25",
                    "3.25" to "3.5",
                    "3.5" to "3.75",
                    "3.75" to "4.0",
                    "4.0" to "4.25",
                    "4.25" to "4.5",
                )
            var index = 1
            pairs.forEach { (lower, higher) ->
                listOf(higher to lower, lower to higher).forEach { (p1, p2) ->
                    sweepScores.forEach { score ->
                        add(generatedScenario(id = "CG$index", group = "Competitive", ntrpP1 = p1, ntrpP2 = p2, score = score))
                        index++
                    }
                }
            }
        }

    /**
     * Extreme matchup: 2.0 vs 7.0 (5.0 gap, near the full NTRP range), both directions,
     * at every legal set score including the 7-6 tiebreak.
     */
    val extremeGapScenarios: List<RatingScenario> =
        buildList {
            var index = 1
            listOf("7.0" to "2.0", "2.0" to "7.0").forEach { (p1, p2) ->
                allSingleSetScores.forEach { score ->
                    add(generatedScenario(id = "EX$index", group = "Extreme", ntrpP1 = p1, ntrpP2 = p2, score = score))
                    index++
                }
            }
        }

    /**
     * All scenarios: the curated list plus the generated published-level,
     * competitive-gap, and extreme-gap sweeps.
     */
    val allScenarios: List<RatingScenario> =
        curatedScenarios + publishedLevelScenarios + competitiveGapScenarios + extremeGapScenarios

    /**
     * Build a scenario whose expected deltas come from the documented master formula
     * (docs/RATING_CALCULATION_ALGORITHM.md) computed independently with the same
     * 6-decimal BigDecimal precision as the calculator. UTR ratings mirror the NTRP
     * matchup via utr = ntrp × 2.5 − 1.5, which preserves the 2.5× gap scaling and
     * maps 7.0 → 16.0 (both ceilings).
     */
    private fun generatedScenario(
        id: String,
        group: String,
        ntrpP1: String,
        ntrpP2: String,
        score: Pair<Int, Int>,
    ): RatingScenario {
        val (gamesWon, gamesLost) = score
        val outcome =
            when {
                ntrpP1.toDouble() > ntrpP2.toDouble() -> "expected"
                ntrpP1.toDouble() < ntrpP2.toDouble() -> "upset"
                else -> "equal"
            }
        val ntrp = expectedDeltas(p1 = ntrpP1, p2 = ntrpP2, constants = ntrpConstants, score = score)
        return RatingScenario(
            id = id,
            description = "$group: $ntrpP1 vs $ntrpP2, $outcome ($gamesWon-$gamesLost)",
            ntrpP1 = ntrpP1,
            ntrpP2 = ntrpP2,
            p1Games = gamesWon,
            p2Games = gamesLost,
            winner = "T1",
            expectedNtrpP1Delta = ntrp.first,
            expectedNtrpP2Delta = ntrp.second,
        )
    }

    /**
     * The master formula from first principles: change = K × dominance × scale × sign,
     * then boundary clamping — the independent oracle for the generated scenarios.
     */
    private fun expectedDeltas(
        p1: String,
        p2: String,
        constants: SystemConstants,
        score: Pair<Int, Int>,
    ): Pair<String, String> {
        val (gamesWon, gamesLost) = score
        val zero = "0.0".bd
        val threshold = "0.083".bd
        val p1Rating = p1.bd
        val p2Rating = p2.bd
        val dominance = (gamesWon - gamesLost).bd.divideBy(divisor = (gamesWon + gamesLost).bd)
        val normalizedGap = (p1Rating - p2Rating).abs().divideBy(divisor = constants.ratingRange)
        val isUpset = p1Rating < p2Rating
        val scale =
            if (isUpset) {
                normalizedGap.divideBy(divisor = threshold) * "2.0".bd
            } else {
                (threshold - normalizedGap).divideBy(divisor = threshold).max(zero)
            }
        val rawChange = constants.kFactor * dominance * scale
        val p1New = (p1Rating + rawChange).coerceIn(constants.minRating, constants.maxRating)
        val p2New = (p2Rating - rawChange).coerceIn(constants.minRating, constants.maxRating)
        val p1Delta = (p1New.toStringPrecise().bd - p1Rating).toStringPrecise()
        val p2Delta = (p2New.toStringPrecise().bd - p2Rating).toStringPrecise()
        return p1Delta to p2Delta
    }
}
