package org.skopeo.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.skopeo.service.calculator.impl.toStringPrecise

@DisplayName("MatchScore dominance factor")
class MatchScoreTest {
    private fun matchOf(vararg setGames: Pair<Int, Int>): MatchScore =
        MatchScore(
            sets =
                setGames.map { (t1Games, t2Games) ->
                    SetScore(
                        games = mapOf("T1" to t1Games, "T2" to t2Games),
                        winner = if (t1Games > t2Games) "T1" else "T2",
                        tiebreak =
                            if (setOf(t1Games, t2Games) == setOf(7, 6)) {
                                TiebreakScore(points = mapOf("T1" to 7, "T2" to 5), winner = if (t1Games > t2Games) "T1" else "T2")
                            } else {
                                null
                            },
                    )
                },
            winner = "T1",
        )

    @Nested
    @DisplayName("Single set (base case: match dominance = set dominance)")
    inner class SingleSet {
        @Test
        @DisplayName("6-0 shutout is maximum dominance 1.0")
        fun testShutout() {
            matchOf(6 to 0).calculateDominanceFactor(teamId = "T1").toStringPrecise() shouldBe "1.000000"
        }

        @Test
        @DisplayName("6-4 is (6-4)/10 = 0.2")
        fun testCloseSet() {
            matchOf(6 to 4).calculateDominanceFactor(teamId = "T1").toStringPrecise() shouldBe "0.200000"
        }

        @Test
        @DisplayName("7-6 tiebreak is (7-6)/13 ≈ 0.077")
        fun testTiebreakSet() {
            matchOf(7 to 6).calculateDominanceFactor(teamId = "T1").toStringPrecise() shouldBe "0.076923"
        }

        @Test
        @DisplayName("Loser's dominance is the exact negative of the winner's")
        fun testLoserSymmetry() {
            val match = matchOf(6 to 2)
            match.calculateDominanceFactor(teamId = "T1").toStringPrecise() shouldBe "0.500000"
            match.calculateDominanceFactor(teamId = "T2").toStringPrecise() shouldBe "-0.500000"
        }
    }

    @Nested
    @DisplayName("Multi-set matches (match dominance = average of per-set dominance)")
    inner class MultiSet {
        @Test
        @DisplayName("Identical sets average to the single-set value: 6-4, 6-4 = 0.2")
        fun testIdenticalSets() {
            matchOf(6 to 4, 6 to 4).calculateDominanceFactor(teamId = "T1").toStringPrecise() shouldBe "0.200000"
        }

        @Test
        @DisplayName("Straight sets 6-3, 7-5 averages (0.333333 + 0.166667)/2 = 0.25")
        fun testStraightSetsAverage() {
            matchOf(6 to 3, 7 to 5).calculateDominanceFactor(teamId = "T1").toStringPrecise() shouldBe "0.250000"
        }

        @Test
        @DisplayName("Split sets 6-0, 3-6, 6-2 average (1.0 - 0.333333 + 0.5)/3 ≈ 0.388889, not pooled games")
        fun testSplitSetsAreAveragedNotPooled() {
            // Pooling game totals would give (15-8)/23 ≈ 0.304348; per-set averaging must win out
            matchOf(6 to 0, 3 to 6, 6 to 2).calculateDominanceFactor(teamId = "T1").toStringPrecise() shouldBe "0.388889"
        }

        @Test
        @DisplayName("Lost set drags the average down: 6-4, 4-6, 6-4 = 0.2/3 ≈ 0.066667")
        fun testThreeSetterDilution() {
            matchOf(6 to 4, 4 to 6, 6 to 4).calculateDominanceFactor(teamId = "T1").toStringPrecise() shouldBe "0.066667"
        }

        @Test
        @DisplayName("Loser symmetry holds across sets")
        fun testMultiSetLoserSymmetry() {
            val match = matchOf(6 to 0, 3 to 6, 6 to 2)
            match.calculateDominanceFactor(teamId = "T2").toStringPrecise() shouldBe "-0.388889"
        }
    }
}
