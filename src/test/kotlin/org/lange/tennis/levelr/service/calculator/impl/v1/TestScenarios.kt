package org.lange.tennis.levelr.service.calculator.impl.v1

/**
 * Shared test scenarios for NTRP and UTR rating calculations.
 * Used by both PerformanceBasedRankingCalculatorImplTest and NTRPvsUTRComparison.
 */
data class RatingScenario(
    val id: String,
    val description: String,
    val ntrpP1: String,
    val ntrpP2: String,
    val utrP1: String,
    val utrP2: String,
    val p1Games: Int,
    val p2Games: Int,
    val winner: String,
    val expectedNtrpP1Delta: String,
    val expectedNtrpP2Delta: String,
    val expectedUtrP1Delta: String,
    val expectedUtrP2Delta: String,
)

object TestScenarios {
    /**
     * All test scenarios covering various rating levels and match outcomes.
     * Scenarios are sorted by rating level (low to high).
     *
     * NTRP uses 0.5 competitive threshold (8.3% of 6.0 range).
     * UTR uses 1.25 competitive threshold (8.3% of 15.0 range).
     * K-factor scaling: K_UTR = 2.5 × K_NTRP, so UTR deltas = 2.5 × NTRP deltas.
     */
    val allScenarios =
        listOf(
            // NTRP 2.5 level (UTR 5.0 level)
            RatingScenario(
                "S1", "Low: Equal players, dominant (6-0)",
                "2.5", "2.5", "5.0", "5.0",
                6, 0, "P1",
                "0.160000", "-0.160000",
                "0.400000", "-0.400000",
            ),
            RatingScenario(
                "S2", "Low: Equal players, close (6-4)",
                "2.5", "2.5", "5.0", "5.0",
                6, 4, "P1",
                "0.032000", "-0.032000",
                "0.080000", "-0.080000",
            ),
            RatingScenario(
                "S3", "Low: Below threshold, 0.1 gap (6-0)",
                "2.6", "2.5", "5.25", "5.0",
                6, 0, "P1",
                "0.127871", "-0.127871",
                "0.319677", "-0.319677",
            ),
            RatingScenario(
                "S4", "Low: 0.5 gap, expected win (6-0)",
                "3.0", "2.5", "6.25", "5.0",
                6, 0, "P1",
                "0.000000", "0.000000",
                "0.000000", "0.000000",
            ),
            RatingScenario(
                "S5", "Low: 0.5 gap, expected win (6-4)",
                "3.0", "2.5", "6.25", "5.0",
                6, 4, "P1",
                "0.000000", "0.000000",
                "0.000000", "0.000000",
            ),
            RatingScenario(
                "S6", "Low: Below threshold, 0.1 gap (6-0)",
                "3.1", "3.0", "6.5", "6.25",
                6, 0, "P1",
                "0.127871", "-0.127871",
                "0.319677", "-0.319677",
            ),
            RatingScenario(
                "S7", "Low: 1.0 gap, expected win (6-0)",
                "3.5", "2.5", "7.5", "5.0",
                6, 0, "P1",
                "0.000000", "0.000000",
                "0.000000", "0.000000",
            ),
            RatingScenario(
                "S8", "Low: 1.0 gap, upset (6-0)",
                "2.5", "3.5", "5.0", "7.5",
                6, 0, "P1",
                "0.642572", "-0.642572",
                "1.606429", "-1.606429",
            ),
            RatingScenario(
                "S9", "Low: Below threshold, 0.1 gap (6-0)",
                "3.6", "3.5", "7.75", "7.5",
                6, 0, "P1",
                "0.127871", "-0.127871",
                "0.319677", "-0.319677",
            ),
            RatingScenario(
                "S10", "Low: 1.5 gap, expected win (6-0)",
                "4.0", "2.5", "8.75", "5.0",
                6, 0, "P1",
                "0.000000", "0.000000",
                "0.000000", "0.000000",
            ),
            RatingScenario(
                "S11", "Low: Below threshold, 0.1 gap (6-0)",
                "4.1", "4.0", "9.0", "8.75",
                6, 0, "P1",
                "0.127871", "-0.127871",
                "0.319677", "-0.319677",
            ),
            // NTRP 4.0-4.5 level (UTR 8.75-10.0 level)
            RatingScenario(
                "S12", "Mid: 2.0 gap, big upset (6-0)",
                "2.5", "4.5", "5.0", "10.0",
                6, 0, "P1",
                "1.285139", "-1.285139",
                "3.212848", "-3.212848",
            ),
            RatingScenario(
                "S13", "Mid: Expected win, dominant (6-0)",
                "4.5", "4.0", "10.0", "8.75",
                6, 0, "P1",
                "0.000000", "0.000000",
                "0.000000", "0.000000",
            ),
            RatingScenario(
                "S14", "Mid: Expected win, close (6-4)",
                "4.5", "4.0", "10.0", "8.75",
                6, 4, "P1",
                "0.000000", "0.000000",
                "0.000000", "0.000000",
            ),
            RatingScenario(
                "S15", "Mid: Upset, dominant (6-0)",
                "4.0", "4.5", "8.75", "10.0",
                6, 0, "P1",
                "0.321284", "-0.321284",
                "0.803210", "-0.803210",
            ),
            RatingScenario(
                "S16", "Mid: Upset, close (6-4)",
                "4.0", "4.5", "8.75", "10.0",
                6, 4, "P1",
                "0.064257", "-0.064257",
                "0.160642", "-0.160642",
            ),
            RatingScenario(
                "S17", "Mid: Competitive, dominant (6-0)",
                "4.5", "4.3", "10.0", "9.5",
                6, 0, "P1",
                "0.095744", "-0.095744",
                "0.239359", "-0.239359",
            ),
            RatingScenario(
                "S18", "Mid: Competitive, close (6-4)",
                "4.5", "4.3", "10.0", "9.5",
                6, 4, "P1",
                "0.019149", "-0.019149",
                "0.047872", "-0.047872",
            ),
            RatingScenario(
                "S19", "Mid: Equal players, dominant (6-0)",
                "4.5", "4.5", "10.0", "10.0",
                6, 0, "P1",
                "0.160000", "-0.160000",
                "0.400000", "-0.400000",
            ),
            RatingScenario(
                "S20", "Mid: Equal players, close (6-4)",
                "4.5", "4.5", "10.0", "10.0",
                6, 4, "P1",
                "0.032000", "-0.032000",
                "0.080000", "-0.080000",
            ),
            RatingScenario(
                "S21", "Mid: Below threshold, 0.1 gap (6-0)",
                "4.6", "4.5", "10.25", "10.0",
                6, 0, "P1",
                "0.127871", "-0.127871",
                "0.319677", "-0.319677",
            ),
            // NTRP 4.0-5.0 level (UTR 10.0-12.5 level) - Large gaps
            RatingScenario(
                "S22", "High: Large gap, expected win (6-0)",
                "5.0", "4.0", "12.5", "10.0",
                6, 0, "P1",
                "0.000000", "0.000000",
                "0.000000", "0.000000",
            ),
            RatingScenario(
                "S23", "High: Large gap, big upset (6-0)",
                "4.0", "5.0", "10.0", "12.5",
                6, 0, "P1",
                "0.642572", "-0.642572",
                "1.606429", "-1.606429",
            ),
            RatingScenario(
                "S24", "High: Below threshold, 0.1 gap (6-0)",
                "5.1", "5.0", "12.75", "12.5",
                6, 0, "P1",
                "0.127871", "-0.127871",
                "0.319677", "-0.319677",
            ),
        )
}
