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

@DisplayName("SetScore")
class SetScoreTest {
    @Nested
    @DisplayName("Winner and loser constructor properties")
    inner class WinnerAndLoser {
        @Test
        @DisplayName("Loser defaults to the team that is not the winner")
        fun testLoserDerivedFromWinner() {
            val set = SetScore(games = mapOf("T1" to 6, "T2" to 3), winnerTeamId = "T1")

            set.loserTeamId shouldBe "T2"
        }

        @Test
        @DisplayName("Winner defaults to the team with the most games, loser to the fewest")
        fun testWinnerAndLoserDerivedFromGames() {
            val set = SetScore(games = mapOf("T1" to 3, "T2" to 6))

            set.winnerTeamId shouldBe "T2"
            set.loserTeamId shouldBe "T1"
        }

        @Test
        @DisplayName("Explicit loser is accepted when consistent")
        fun testExplicitLoserAccepted() {
            val set = SetScore(games = mapOf("T1" to 2, "T2" to 6), winnerTeamId = "T2", loserTeamId = "T1")

            set.loserTeamId shouldBe "T1"
        }

        @Test
        @DisplayName("Loser equal to winner is rejected")
        fun testLoserEqualToWinnerRejected() {
            val exception =
                shouldThrow<IllegalArgumentException> {
                    SetScore(games = mapOf("T1" to 6, "T2" to 3), winnerTeamId = "T1", loserTeamId = "T1")
                }

            exception.message shouldContain "must differ from the set winner"
        }

        @Test
        @DisplayName("Loser that is not one of the teams is rejected")
        fun testUnknownLoserRejected() {
            val exception =
                shouldThrow<IllegalArgumentException> {
                    SetScore(games = mapOf("T1" to 6, "T2" to 3), winnerTeamId = "T1", loserTeamId = "T3")
                }

            exception.message shouldContain "must be one of the teams"
        }

        @Test
        @DisplayName("Payload without loser derives it during deserialization")
        fun testPayloadWithoutLoser() {
            val json = """{"games":{"T1":6,"T2":4},"winnerTeamId":"T1"}"""

            val set = Json.decodeFromString<SetScore>(string = json)

            set.loserTeamId shouldBe "T2"
        }

        @Test
        @DisplayName("Payload with games only derives both winner and loser")
        fun testPayloadWithGamesOnly() {
            val json = """{"games":{"T1":4,"T2":6}}"""

            val set = Json.decodeFromString<SetScore>(string = json)

            set.winnerTeamId shouldBe "T2"
            set.loserTeamId shouldBe "T1"
        }

        @Test
        @DisplayName("Payload may state both winner and loser explicitly")
        fun testPayloadWithExplicitWinnerAndLoser() {
            val json = """{"games":{"T1":4,"T2":6},"winnerTeamId":"T2","loserTeamId":"T1"}"""

            val set = Json.decodeFromString<SetScore>(string = json)

            set.winnerTeamId shouldBe "T2"
            set.loserTeamId shouldBe "T1"
        }
    }

    @Nested
    @DisplayName("Tiebreak validation")
    inner class TiebreakValidation {
        @Test
        @DisplayName("Tiebreak won by the set winner is accepted")
        fun testTiebreakWonBySetWinnerAccepted() {
            val set =
                SetScore(
                    games = mapOf("T1" to 7, "T2" to 6),
                    winnerTeamId = "T1",
                    tiebreak = TiebreakScore(points = mapOf("T1" to 7, "T2" to 5), winnerTeamId = "T1"),
                )

            set.winnerTeamId shouldBe "T1"
            set.tiebreak?.winnerTeamId shouldBe "T1"
        }

        @Test
        @DisplayName("Tiebreak won by a team other than the set winner is rejected")
        fun testTiebreakWonByOtherTeamRejected() {
            val exception =
                shouldThrow<IllegalArgumentException> {
                    SetScore(
                        games = mapOf("T1" to 7, "T2" to 6),
                        winnerTeamId = "T1",
                        tiebreak = TiebreakScore(points = mapOf("T1" to 5, "T2" to 7), winnerTeamId = "T2"),
                    )
                }

            exception.message shouldContain "must be the set winner"
        }

        @Test
        @DisplayName("Tiebreak loser defaults to the team that is not the tiebreak winner")
        fun testTiebreakLoserDerivedFromWinner() {
            val tiebreak = TiebreakScore(points = mapOf("T1" to 7, "T2" to 5), winnerTeamId = "T1")

            tiebreak.loserTeamId shouldBe "T2"
        }

        @Test
        @DisplayName("Tiebreak winner defaults to the team with the most points, loser to the fewest")
        fun testTiebreakWinnerAndLoserDerivedFromPoints() {
            val tiebreak = TiebreakScore(points = mapOf("T1" to 5, "T2" to 7))

            tiebreak.winnerTeamId shouldBe "T2"
            tiebreak.loserTeamId shouldBe "T1"
        }

        @Test
        @DisplayName("Tiebreak loser equal to the tiebreak winner is rejected")
        fun testTiebreakLoserEqualToWinnerRejected() {
            val exception =
                shouldThrow<IllegalArgumentException> {
                    TiebreakScore(points = mapOf("T1" to 7, "T2" to 5), winnerTeamId = "T1", loserTeamId = "T1")
                }

            exception.message shouldContain "must differ from the tiebreak winner"
        }

        @Test
        @DisplayName("Tiebreak loser that is not one of the teams is rejected")
        fun testTiebreakUnknownLoserRejected() {
            val exception =
                shouldThrow<IllegalArgumentException> {
                    TiebreakScore(points = mapOf("T1" to 7, "T2" to 5), winnerTeamId = "T1", loserTeamId = "T3")
                }

            exception.message shouldContain "must be one of the teams in the tiebreak"
        }
    }

    @Nested
    @DisplayName("Flexible scores (format policy lives at recording, #213)")
    inner class FlexibleScores {
        @Test
        @DisplayName("A set won by a single game is accepted (no win-by-2 rule)")
        fun testWinByOneAccepted() {
            val set = SetScore(games = mapOf("T1" to 6, "T2" to 5), winnerTeamId = "T1")

            set.winnerTeamId shouldBe "T1"
            set.loserTeamId shouldBe "T2"
        }

        @Test
        @DisplayName("A short set is accepted (no 6-game floor)")
        fun testShortSetAccepted() {
            val set = SetScore(games = mapOf("T1" to 4, "T2" to 1), winnerTeamId = "T1")

            set.winnerTeamId shouldBe "T1"
        }

        @Test
        @DisplayName("A long set is accepted (no 7-game cap)")
        fun testLongSetAccepted() {
            val set = SetScore(games = mapOf("T1" to 8, "T2" to 6), winnerTeamId = "T1")

            set.winnerTeamId shouldBe "T1"
        }

        @Test
        @DisplayName("A tiebreak-decided set need not be 7-6")
        fun testTiebreakSetNeedNotBeSevenSix() {
            val set =
                SetScore(
                    games = mapOf("T1" to 7, "T2" to 5),
                    winnerTeamId = "T1",
                    tiebreak = TiebreakScore(points = mapOf("T1" to 10, "T2" to 8), winnerTeamId = "T1"),
                )

            set.tiebreak?.winnerTeamId shouldBe "T1"
        }

        @Test
        @DisplayName("A tiebreak winner other than the set winner is still rejected")
        fun testTiebreakWinnerConsistencyStillEnforced() {
            val exception =
                shouldThrow<IllegalArgumentException> {
                    SetScore(
                        games = mapOf("T1" to 6, "T2" to 5),
                        winnerTeamId = "T1",
                        tiebreak = TiebreakScore(points = mapOf("T1" to 3, "T2" to 7), winnerTeamId = "T2"),
                    )
                }

            exception.message shouldContain "must be the set winner"
        }
    }
}
