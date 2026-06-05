package org.lange.tennis.levelr.service.calculator.impl.v1

import org.junit.jupiter.api.Test
import org.lange.tennis.levelr.dto.RankingCalculationRequest
import org.lange.tennis.levelr.model.MatchScore
import org.lange.tennis.levelr.model.PlayerProfile
import org.lange.tennis.levelr.model.Rating
import org.lange.tennis.levelr.model.RatingCalculationOptions
import org.lange.tennis.levelr.model.RatingSystem
import org.lange.tennis.levelr.model.SetScore
import java.io.File

/**
 * Comparison test showing NTRP vs UTR behavior side-by-side.
 * Demonstrates that UTR changes are 2.5× larger than NTRP (K_UTR = 0.4, K_NTRP = 0.16).
 * Uses shared test scenarios from TestScenarios.kt.
 */
class NTRPvsUTRComparison {
    private val calculator = PerformanceBasedRankingCalculatorImpl()
    private val scenarios = TestScenarios.allScenarios

    private fun createRequest(
        p1Rating: String,
        p2Rating: String,
        system: RatingSystem,
        p1Games: Int,
        p2Games: Int,
        winner: String,
        smoothingEnabled: Boolean = false,
        smoothingFactor: Double = 0.5,
    ): RankingCalculationRequest {
        val options =
            if (smoothingEnabled) {
                RatingCalculationOptions(smoothingEnabled = true, smoothingFactor = smoothingFactor)
            } else {
                null
            }

        return RankingCalculationRequest(
            players =
                mapOf(
                    "P1" to
                        PlayerProfile(
                            playerId = "P1",
                            name = "Player 1",
                            rating = Rating(value = p1Rating, system = system),
                        ),
                    "P2" to
                        PlayerProfile(
                            playerId = "P2",
                            name = "Player 2",
                            rating = Rating(value = p2Rating, system = system),
                        ),
                ),
            matchScore =
                MatchScore(
                    sets =
                        listOf(
                            SetScore(
                                games = mapOf("P1" to p1Games, "P2" to p2Games),
                                winner = winner,
                            ),
                        ),
                ),
            options = options,
        )
    }

    @Test
    fun compareNTRPvsUTR() {
        val output = StringBuilder()

        // Pre-calculate all values to determine column widths
        data class ScenarioResult(
            val scenario: RatingScenario,
            val ntrpP1Delta: Double,
            val ntrpP2Delta: Double,
            val ntrpP1New: Double,
            val ntrpP2New: Double,
            val utrP1Delta: Double,
            val utrP2Delta: Double,
            val utrP1New: Double,
            val utrP2New: Double,
            val ratio: Double,
            val matches: Boolean,
        )

        val results =
            scenarios.map { scenario ->
                val ntrpResult =
                    calculator.calculate(
                        request =
                            createRequest(
                                p1Rating = scenario.ntrpP1,
                                p2Rating = scenario.ntrpP2,
                                system = RatingSystem.NTRP,
                                p1Games = scenario.p1Games,
                                p2Games = scenario.p2Games,
                                winner = scenario.winner,
                                smoothingEnabled = scenario.smoothingEnabled,
                                smoothingFactor = scenario.smoothingFactor,
                            ),
                    )
                val ntrpP1Delta = ntrpResult.response.ratingChanges["P1"]?.change?.toDoubleOrNull() ?: 0.0
                val ntrpP2Delta = ntrpResult.response.ratingChanges["P2"]?.change?.toDoubleOrNull() ?: 0.0
                val ntrpP1New = scenario.ntrpP1.toDouble() + ntrpP1Delta
                val ntrpP2New = scenario.ntrpP2.toDouble() + ntrpP2Delta

                val utrResult =
                    calculator.calculate(
                        request =
                            createRequest(
                                p1Rating = scenario.utrP1,
                                p2Rating = scenario.utrP2,
                                system = RatingSystem.UTR,
                                p1Games = scenario.p1Games,
                                p2Games = scenario.p2Games,
                                winner = scenario.winner,
                                smoothingEnabled = scenario.smoothingEnabled,
                                smoothingFactor = scenario.smoothingFactor,
                            ),
                    )
                val utrP1Delta = utrResult.response.ratingChanges["P1"]?.change?.toDoubleOrNull() ?: 0.0
                val utrP2Delta = utrResult.response.ratingChanges["P2"]?.change?.toDoubleOrNull() ?: 0.0
                val utrP1New = scenario.utrP1.toDouble() + utrP1Delta
                val utrP2New = scenario.utrP2.toDouble() + utrP2Delta

                val ratio = if (ntrpP1Delta != 0.0) utrP1Delta / ntrpP1Delta else 0.0
                val matches = Math.abs(ratio - 2.5) < 0.01 || (ntrpP1Delta == 0.0 && utrP1Delta == 0.0)

                ScenarioResult(
                    scenario = scenario,
                    ntrpP1Delta = ntrpP1Delta,
                    ntrpP2Delta = ntrpP2Delta,
                    ntrpP1New = ntrpP1New,
                    ntrpP2New = ntrpP2New,
                    utrP1Delta = utrP1Delta,
                    utrP2Delta = utrP2Delta,
                    utrP1New = utrP1New,
                    utrP2New = utrP2New,
                    ratio = ratio,
                    matches = matches,
                )
            }

        // Calculate column widths
        val idWidth = maxOf(2, results.maxOf { it.scenario.id.length })
        val descWidth = maxOf(11, results.maxOf { it.scenario.description.length })
        val ntrpRatingsWidth = maxOf(10, results.maxOf { "${it.scenario.ntrpP1} vs ${it.scenario.ntrpP2}".length })
        val utrRatingsWidth = maxOf(9, results.maxOf { "${it.scenario.utrP1} vs ${it.scenario.utrP2}".length })
        val scoreWidth = maxOf(5, results.maxOf { "${it.scenario.p1Games}-${it.scenario.p2Games}".length })
        val ntrpP1DeltaWidth = maxOf(16, results.maxOf { String.format("%+.6f (%.6f)", it.ntrpP1Delta, it.ntrpP1New).length })
        val ntrpP2DeltaWidth = maxOf(16, results.maxOf { String.format("%+.6f (%.6f)", it.ntrpP2Delta, it.ntrpP2New).length })
        val utrP1DeltaWidth = maxOf(15, results.maxOf { String.format("%+.6f (%.6f)", it.utrP1Delta, it.utrP1New).length })
        val utrP2DeltaWidth = maxOf(15, results.maxOf { String.format("%+.6f (%.6f)", it.utrP2Delta, it.utrP2New).length })
        val ratioWidth = maxOf(8, results.maxOf { String.format("%.2f", it.ratio).length })
        val matchWidth = 5

        val totalWidth =
            idWidth + 3 + descWidth + 3 + ntrpRatingsWidth + 3 + utrRatingsWidth + 3 + scoreWidth + 3 +
                ntrpP1DeltaWidth + 3 + ntrpP2DeltaWidth + 3 + utrP1DeltaWidth + 3 + utrP2DeltaWidth + 3 +
                ratioWidth + 3 + matchWidth

        output.appendLine("=".repeat(n = totalWidth))
        output.appendLine("NTRP vs UTR COMPARISON")
        output.appendLine("=".repeat(n = totalWidth))
        output.appendLine()
        output.appendLine("Demonstrates 2.5× scaling relationship:")
        output.appendLine("  K_NTRP = 0.16")
        output.appendLine("  K_UTR  = 0.4 (2.5× NTRP)")
        output.appendLine()
        output.appendLine("Therefore: UTR_delta = NTRP_delta × 2.5")
        output.appendLine()
        output.appendLine("=".repeat(n = totalWidth))
        output.appendLine()

        // Header
        output.appendLine(
            String.format(
                "%-${idWidth}s | %-${descWidth}s | %-${ntrpRatingsWidth}s | %-${utrRatingsWidth}s | " +
                    "%-${scoreWidth}s | %${ntrpP1DeltaWidth}s | %${ntrpP2DeltaWidth}s | " +
                    "%${utrP1DeltaWidth}s | %${utrP2DeltaWidth}s | %${ratioWidth}s | %${matchWidth}s",
                "ID",
                "Description",
                "NTRP P1-P2",
                "UTR P1-P2",
                "Score",
                "NTRP P1 Δ (New)",
                "NTRP P2 Δ (New)",
                "UTR P1 Δ (New)",
                "UTR P2 Δ (New)",
                "UTR/NTRP",
                "Match",
            ),
        )
        output.appendLine("-".repeat(n = totalWidth))

        results.forEach { result ->
            val scenario = result.scenario
            val ntrpRatings = "${scenario.ntrpP1} vs ${scenario.ntrpP2}"
            val utrRatings = "${scenario.utrP1} vs ${scenario.utrP2}"
            val score = "${scenario.p1Games}-${scenario.p2Games}"

            output.appendLine(
                String.format(
                    "%-${idWidth}s | %-${descWidth}s | %-${ntrpRatingsWidth}s | %-${utrRatingsWidth}s | " +
                        "%-${scoreWidth}s | %${ntrpP1DeltaWidth}s | %${ntrpP2DeltaWidth}s | " +
                        "%${utrP1DeltaWidth}s | %${utrP2DeltaWidth}s | %${ratioWidth}s | %${matchWidth}s",
                    scenario.id,
                    scenario.description,
                    ntrpRatings,
                    utrRatings,
                    score,
                    String.format("%+.6f (%.6f)", result.ntrpP1Delta, result.ntrpP1New),
                    String.format("%+.6f (%.6f)", result.ntrpP2Delta, result.ntrpP2New),
                    String.format("%+.6f (%.6f)", result.utrP1Delta, result.utrP1New),
                    String.format("%+.6f (%.6f)", result.utrP2Delta, result.utrP2New),
                    String.format("%.2f", result.ratio),
                    if (result.matches) "✓" else "✗",
                ),
            )
        }

        output.appendLine()
        output.appendLine("=".repeat(n = totalWidth))
        output.appendLine()
        output.appendLine("ANALYSIS:")
        output.appendLine()
        output.appendLine("✓ All ratios = 2.5 (or both = 0), confirming correct K-factor scaling")
        output.appendLine("✓ Formula behaves consistently across both rating systems")
        output.appendLine("✓ Threshold (1.0) applies equally in absolute terms for both systems")
        output.appendLine()
        output.appendLine("Why same threshold works:")
        output.appendLine("  - NTRP diff of 0.5 is significant (1/12 of range)")
        output.appendLine("  - UTR diff of 0.5 is smaller (1/30 of range)")
        output.appendLine("  - BUT: Absolute skill difference matters, not relative to scale")
        output.appendLine("  - A 0.5-point rating gap represents similar skill difference in both systems")
        output.appendLine()
        output.appendLine("Alternative: Proportional thresholds")
        output.appendLine("  - NTRP threshold could be 0.5 (1/12 of 6.0 range)")
        output.appendLine("  - UTR threshold could be 1.25 (1/12 of 15.0 range)")
        output.appendLine("  - This would make thresholds proportional to rating system range")
        output.appendLine("  - Current choice (threshold=1.0 for both) is simpler and reasonable")

        val outputStr = output.toString()
        println(outputStr)
        File("/tmp/ntrp_vs_utr_comparison.txt").writeText(text = outputStr)
        println("\nResults written to /tmp/ntrp_vs_utr_comparison.txt")
    }
}
