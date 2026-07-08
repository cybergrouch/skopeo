// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.calculator.impl.v2

import org.skopeo.dto.RankingCalculationRequest
import org.skopeo.model.MatchScore
import org.skopeo.model.PlayerProfile
import org.skopeo.model.Rating
import org.skopeo.model.RatingCalculationOptions
import org.skopeo.model.SetScore
import org.skopeo.model.Team
import org.skopeo.model.TeamType
import java.io.File
import java.math.RoundingMode
import java.util.Locale

/**
 * Doubles analogue of [NtrpMatchupMatrixReport] (#256): how the **dominance** (game margin) of a
 * doubles win, and the **within-team NTRP gap** of the winning pair, shape each partner's final
 * rating under the shipped v2 team-mean scheme ([DoublesMatchTypeHandler]).
 *
 * To isolate the gap effect, every winning pair is held at the **same team mean (3.5)** and plays the
 * **same even opponent** (a 3.5 + 3.5 pair), so the team-level change is identical across pairings and
 * only the *split* between partners varies. Five pairings by within-team gap: 0.0, 0.5, 1.0, 1.5, 2.0.
 * The score is swept over every legal single-set result (6-0 … 7-6). What-if endpoint, so the
 * match-type factor is 1.0.
 *
 * Run with `./gradlew generateDoublesDominanceReport`. Magic numbers, flat params, and unnamed args are
 * inherent to a numeric report, so those style rules are suppressed here.
 */
@Suppress("MagicNumber", "NamedArguments", "LongParameterList")
class DoublesDominanceReport {
    private val calculator = PerformanceBasedRankingCalculatorImpl()

    private companion object {
        const val TEAM_MEAN = 3.5
        const val FACTOR = 1.0
        val GAPS = listOf(0.0, 0.5, 1.0, 1.5, 2.0)

        // Losses: equal partners (gap 0) lose equally, so the loss study covers the non-trivial pairings.
        val LOSS_GAPS = listOf(0.5, 1.0, 1.5, 2.0)

        // Legal single-set scores from the winner's perspective (7-6 implies a tiebreak).
        val SCORES = listOf(6 to 0, 6 to 1, 6 to 2, 6 to 3, 6 to 4, 7 to 5, 7 to 6)
    }

    /** One winning pair's outcome for a score: each partner's rating change and the team-mean move. */
    private data class Split(
        val strongDelta: Double,
        val weakDelta: Double,
        val teamDelta: Double,
    )

    private fun ratingOf(value: Double): Rating {
        val clamped = value.coerceIn(minimumValue = 1.0, maximumValue = 7.0).toBigDecimal().setScale(6, RoundingMode.HALF_UP)
        return Rating.fromValue(value = clamped.toPlainString())
    }

    private fun doublesTeam(
        id: String,
        strong: Double,
        weak: Double,
    ): Team =
        Team(
            teamId = id,
            name = id,
            players =
                listOf(
                    PlayerProfile(playerId = "$id-strong", name = "$id-strong", rating = ratingOf(value = strong)),
                    PlayerProfile(playerId = "$id-weak", name = "$id-weak", rating = ratingOf(value = weak)),
                ),
            teamType = TeamType.DOUBLES,
        )

    /**
     * Run one doubles match for pair A (the given within-team gap, at mean [TEAM_MEAN]) vs an even
     * 3.5 + 3.5 pair, at a winner-perspective score. [aWins] decides whether A wins or loses; the
     * returned deltas are A's (positive on a win, negative on a loss).
     */
    private fun splitFor(
        gap: Double,
        gamesWon: Int,
        gamesLost: Int,
        aWins: Boolean,
    ): Split {
        val strong = TEAM_MEAN + gap / 2
        val weak = TEAM_MEAN - gap / 2
        val aGames = if (aWins) gamesWon else gamesLost
        val bGames = if (aWins) gamesLost else gamesWon
        val request =
            RankingCalculationRequest(
                teams =
                    mapOf(
                        "A" to doublesTeam(id = "A", strong = strong, weak = weak),
                        "B" to doublesTeam(id = "B", strong = TEAM_MEAN, weak = TEAM_MEAN),
                    ),
                matchScore =
                    MatchScore(
                        sets =
                            listOf(
                                element =
                                    SetScore(
                                        games = mapOf("A" to aGames, "B" to bGames),
                                        winnerTeamId = if (aWins) "A" else "B",
                                    ),
                            ),
                    ),
                options = RatingCalculationOptions(matchTypeFactor = FACTOR),
            )
        val changes = calculator.calculate(request = request).response.ratingChanges
        val strongDelta = changes.getValue(key = "A-strong").change.toDouble()
        val weakDelta = changes.getValue(key = "A-weak").change.toDouble()
        return Split(strongDelta = strongDelta, weakDelta = weakDelta, teamDelta = (strongDelta + weakDelta) / 2)
    }

    private fun dominance(
        gamesWon: Int,
        gamesLost: Int,
    ): Double = (gamesWon - gamesLost).toDouble() / (gamesWon + gamesLost)

    private fun f(value: Double): String = String.format(Locale.US, "%+.6f", value)

    private fun f2(value: Double): String = String.format(Locale.US, "%.4f", value)

    /** A score (rows) × within-team-gap (columns) table of A's per-partner rating change (stronger / weaker). */
    private fun scoreTable(
        gaps: List<Double>,
        aWins: Boolean,
    ): String =
        buildString {
            append("| Score | Dominance | Team Δ |")
            gaps.forEach { append(" gap ${f2(it)} (S / W) |") }
            appendLine()
            append("|---|---|---|")
            gaps.forEach { append("---|") }
            appendLine()
            SCORES.forEach { (won, lost) ->
                val splits = gaps.associateWith { splitFor(gap = it, gamesWon = won, gamesLost = lost, aWins = aWins) }
                val teamDelta = splits.getValue(key = gaps.first()).teamDelta
                append("| $won-$lost | ${f2(dominance(gamesWon = won, gamesLost = lost))} | ${f(teamDelta)} |")
                gaps.forEach { gap ->
                    val s = splits.getValue(key = gap)
                    append(" ${f(s.strongDelta)} / ${f(s.weakDelta)} |")
                }
                appendLine()
            }
        }

    fun renderMarkdown(): String =
        buildString {
            appendLine("### Pairings (all at team mean 3.5, vs an even 3.5 + 3.5 opponent)")
            appendLine()
            appendLine("| Within-team gap | Weaker partner | Stronger partner |")
            appendLine("|---|---|---|")
            GAPS.forEach { gap ->
                appendLine("| ${f2(gap)} | ${f2(TEAM_MEAN - gap / 2)} | ${f2(TEAM_MEAN + gap / 2)} |")
            }
            appendLine()
            appendLine("### Wins — rating change by dominance (score) and within-team gap")
            appendLine()
            appendLine("Each cell is the winning partners' change as **stronger Δ / weaker Δ**. **Team Δ** is the team-mean")
            appendLine("move — identical across pairings for a given score (shared mean + opponent). Match-type factor 1.0.")
            appendLine()
            append(scoreTable(gaps = GAPS, aWins = true))
            appendLine()
            appendLine("### Losses — rating change by dominance and within-team gap")
            appendLine()
            appendLine("The same pairs losing the even match (equal partners omitted — they lose equally). Each partner's")
            appendLine("loss is proportional to their share of the team mean, so this is the exact negation of the win table.")
            appendLine()
            append(scoreTable(gaps = LOSS_GAPS, aWins = false))
        }
}

/** Run the report directly (IDE, or `./gradlew generateDoublesDominanceReport`). */
@Suppress("NamedArguments") // java.io.File's constructor takes no Kotlin-nameable parameters
fun main() {
    val markdown = DoublesDominanceReport().renderMarkdown()
    println(markdown)
    File("/tmp/doubles_dominance.md").writeText(markdown)
    File("presentations").mkdirs()
    File("presentations/doubles_dominance.md").writeText(markdown)
    println("Results written to /tmp/doubles_dominance.md and presentations/doubles_dominance.md")
}
