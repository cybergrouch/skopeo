// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("MatchScore dominance factor")
class MatchScoreTest {
    private fun matchOf(vararg setGames: Pair<Int, Int>): MatchScore =
        MatchScore(
            sets =
                setGames.map { (t1Games, t2Games) ->
                    SetScore(
                        games = mapOf("T1" to t1Games, "T2" to t2Games),
                        winnerTeamId = if (t1Games > t2Games) "T1" else "T2",
                        tiebreak =
                            if (setOf(t1Games, t2Games) == setOf(7, 6)) {
                                TiebreakScore(points = mapOf("T1" to 7, "T2" to 5), winnerTeamId = if (t1Games > t2Games) "T1" else "T2")
                            } else {
                                null
                            },
                    )
                },
            winnerTeamId = "T1",
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

    @Nested
    @DisplayName("Winner and loser constructor properties")
    inner class WinnerAndLoser {
        private fun setsOf(vararg setGames: Pair<Int, Int>): List<SetScore> =
            setGames.map { (t1Games, t2Games) ->
                SetScore(
                    games = mapOf("T1" to t1Games, "T2" to t2Games),
                    winnerTeamId = if (t1Games > t2Games) "T1" else "T2",
                )
            }

        @Test
        @DisplayName("Loser defaults to the team that is not the winner")
        fun testLoserDerivedFromWinner() {
            val match = MatchScore(sets = setsOf(6 to 4), winnerTeamId = "T1")

            match.loserTeamId shouldBe "T2"
        }

        @Test
        @DisplayName("Loser is derived correctly when the match winner lost the first set")
        fun testLoserDerivedInSplitSetMatch() {
            // T2 takes the first set; match winner is still T1
            val match = MatchScore(sets = setsOf(3 to 6, 6 to 2, 6 to 4), winnerTeamId = "T1")

            match.loserTeamId shouldBe "T2"
        }

        @Test
        @DisplayName("Winner defaults to the team that won the most sets")
        fun testWinnerDerivedFromSetMajority() {
            // T1 wins sets 1 and 3, T2 wins set 2: winner must be T1 even though
            // T2 is the lexicographically larger team ID
            val match = MatchScore(sets = setsOf(6 to 3, 2 to 6, 6 to 4))

            match.winnerTeamId shouldBe "T1"
            match.loserTeamId shouldBe "T2"
        }

        @Test
        @DisplayName("Loser derives correctly when the 2-1 winner has the larger team ID")
        fun testLoserDerivedWhenWinnerDroppedFirstSet() {
            // T2 wins the match 2 sets to 1 after losing the first set; with a
            // lexicographic per-set-loser derivation this used to collide with the winner
            val match = MatchScore(sets = setsOf(6 to 3, 2 to 6, 4 to 6), winnerTeamId = "T2")

            match.winnerTeamId shouldBe "T2"
            match.loserTeamId shouldBe "T1"
        }

        @Test
        @DisplayName("Stated winner that contradicts the set majority is rejected")
        fun testContradictoryWinnerRejected() {
            // T1 won 2 of 3 sets, but the payload claims T2 won the match; the
            // derived loser (most sets lost) is also T2, so validation rejects it
            val exception =
                shouldThrow<IllegalArgumentException> {
                    MatchScore(sets = setsOf(6 to 3, 2 to 6, 6 to 4), winnerTeamId = "T2")
                }

            exception.message shouldContain "must differ from the winner"
        }

        @Test
        @DisplayName("Payload with sets only derives winner and loser from the set majority")
        fun testPayloadWithSetsOnlyDerivesFromSetMajority() {
            // 2-1 split: T1 wins sets 1 and 3
            val json =
                """
                {"sets":[
                  {"games":{"T1":6,"T2":3}},
                  {"games":{"T1":2,"T2":6}},
                  {"games":{"T1":6,"T2":4}}
                ]}
                """.trimIndent()

            val match = Json.decodeFromString<MatchScore>(string = json)

            match.winnerTeamId shouldBe "T1"
            match.loserTeamId shouldBe "T2"
        }

        @Test
        @DisplayName("Explicit loser is accepted when consistent")
        fun testExplicitLoserAccepted() {
            val match = MatchScore(sets = setsOf(6 to 4), winnerTeamId = "T1", loserTeamId = "T2")

            match.loserTeamId shouldBe "T2"
        }

        @Test
        @DisplayName("Loser equal to winner is rejected")
        fun testLoserEqualToWinnerRejected() {
            val exception =
                shouldThrow<IllegalArgumentException> {
                    MatchScore(sets = setsOf(6 to 4), winnerTeamId = "T1", loserTeamId = "T1")
                }

            exception.message shouldContain "must differ from the winner"
        }

        @Test
        @DisplayName("Loser that is not one of the teams is rejected")
        fun testUnknownLoserRejected() {
            val exception =
                shouldThrow<IllegalArgumentException> {
                    MatchScore(sets = setsOf(6 to 4), winnerTeamId = "T1", loserTeamId = "T3")
                }

            exception.message shouldContain "must be one of the teams"
        }

        @Test
        @DisplayName("Winner that is not one of the teams is rejected")
        fun testUnknownWinnerRejected() {
            val exception =
                shouldThrow<IllegalArgumentException> {
                    MatchScore(sets = setsOf(6 to 4), winnerTeamId = "T3")
                }

            exception.message shouldContain "Match winner 'T3'"
        }

        @Test
        @DisplayName("Payload without loser derives it during deserialization")
        fun testPayloadWithoutLoser() {
            val json = """{"sets":[{"games":{"T1":6,"T2":4},"winnerTeamId":"T1"}],"winnerTeamId":"T1"}"""

            val match = Json.decodeFromString<MatchScore>(string = json)

            match.winnerTeamId shouldBe "T1"
            match.loserTeamId shouldBe "T2"
        }

        @Test
        @DisplayName("Payload may state both winner and loser explicitly")
        fun testPayloadWithExplicitWinnerAndLoser() {
            val json = """{"sets":[{"games":{"T1":3,"T2":6},"winnerTeamId":"T2"}],"winnerTeamId":"T2","loserTeamId":"T1"}"""

            val match = Json.decodeFromString<MatchScore>(string = json)

            match.winnerTeamId shouldBe "T2"
            match.loserTeamId shouldBe "T1"
        }

        @Test
        @DisplayName("Payload with inconsistent loser is rejected during deserialization")
        fun testPayloadWithInconsistentLoserRejected() {
            val json = """{"sets":[{"games":{"T1":6,"T2":4},"winnerTeamId":"T1"}],"winnerTeamId":"T1","loserTeamId":"T1"}"""

            shouldThrow<IllegalArgumentException> {
                Json.decodeFromString<MatchScore>(string = json)
            }
        }
    }
}
