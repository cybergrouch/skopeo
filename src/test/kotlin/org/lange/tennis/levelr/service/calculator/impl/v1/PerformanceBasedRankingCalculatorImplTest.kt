package org.lange.tennis.levelr.service.calculator.impl.v1

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.lange.tennis.levelr.dto.RankingCalculationResponse
import org.lange.tennis.levelr.module
import org.lange.tennis.levelr.service.calculator.impl.RankingTestCase
import org.lange.tennis.levelr.service.calculator.impl.Scenarios.scenario_Hw_6_0
import org.lange.tennis.levelr.service.calculator.impl.Scenarios.scenario_Hw_6_1
import org.lange.tennis.levelr.service.calculator.impl.Scenarios.scenario_Hw_6_2
import org.lange.tennis.levelr.service.calculator.impl.Scenarios.scenario_Hw_6_3
import org.lange.tennis.levelr.service.calculator.impl.Scenarios.scenario_Hw_6_4
import org.lange.tennis.levelr.service.calculator.impl.Scenarios.scenario_Hw_7_5
import org.lange.tennis.levelr.service.calculator.impl.Scenarios.scenario_Lw_6_0
import org.lange.tennis.levelr.service.calculator.impl.Scenarios.scenario_Lw_6_1
import org.lange.tennis.levelr.service.calculator.impl.Scenarios.scenario_Lw_6_3
import org.lange.tennis.levelr.service.calculator.impl.Scenarios.scenario_Lw_6_4
import org.lange.tennis.levelr.service.calculator.impl.Scenarios.scenario_Lw_7_5
import kotlin.math.abs
import kotlin.test.Test
import io.kotest.matchers.ints.shouldBeGreaterThan as intsShouldBeGreaterThan

class PerformanceBasedRankingCalculatorImplTest {
    companion object {
        @JvmStatic
        fun rankingTestCases() =
            listOf(
                RankingTestCase(
                    scenario = scenario_Hw_6_0,
                    expectedPlayer1Rating = "4.666667",
                    expectedPlayer2Rating = "3.833333",
                ),
                RankingTestCase(
                    scenario = scenario_Hw_6_1,
                    expectedPlayer1Rating = "4.666667",
                    expectedPlayer2Rating = "3.833333",
                ),
                RankingTestCase(
                    scenario = scenario_Hw_6_2,
                    expectedPlayer1Rating = "4.633141",
                    expectedPlayer2Rating = "3.866859",
                ),
                RankingTestCase(
                    scenario = scenario_Hw_6_3,
                    expectedPlayer1Rating = "4.500000",
                    expectedPlayer2Rating = "4.000000",
                ),
                RankingTestCase(
                    scenario = scenario_Hw_6_4,
                    expectedPlayer1Rating = "4.460058",
                    expectedPlayer2Rating = "4.039942",
                ),
                RankingTestCase(
                    scenario = scenario_Hw_7_5,
                    expectedPlayer1Rating = "4.462720",
                    expectedPlayer2Rating = "4.037280",
                ),
                RankingTestCase(
                    scenario = scenario_Lw_6_0,
                    expectedPlayer1Rating = "4.166667",
                    expectedPlayer2Rating = "4.333333",
                ),
                RankingTestCase(
                    scenario = scenario_Lw_6_1,
                    expectedPlayer1Rating = "4.166667",
                    expectedPlayer2Rating = "4.333333",
                ),
                RankingTestCase(
                    scenario = scenario_Lw_6_3,
                    expectedPlayer1Rating = "4.166667",
                    expectedPlayer2Rating = "4.333333",
                ),
                RankingTestCase(
                    scenario = scenario_Lw_6_4,
                    expectedPlayer1Rating = "4.166667",
                    expectedPlayer2Rating = "4.333333",
                ),
                RankingTestCase(
                    scenario = scenario_Lw_7_5,
                    expectedPlayer1Rating = "4.166667",
                    expectedPlayer2Rating = "4.333333",
                ),
            )
    }

    private fun assertErrorResponse(
        status: HttpStatusCode,
        body: String,
    ) {
        status.value intsShouldBeGreaterThan 399
        body.shouldNotBeEmpty()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rankingTestCases")
    fun testValidRankingCalculation(testCase: RankingTestCase) =
        testApplication {
            application {
                module()
            }

            val client =
                createClient {
                    install(ContentNegotiation) {
                        json(
                            Json {
                                ignoreUnknownKeys = true
                            },
                        )
                    }
                }

            val response =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "players": {
                            "P1": {
                              "playerId": "P1",
                              "name": "Player 1",
                              "rating": {
                                "value": "${testCase.scenario.player1Rating}",
                                "system": "${testCase.scenario.ratingSystem}"
                              }
                            },
                            "P2": {
                              "playerId": "P2",
                              "name": "Player 2",
                              "rating": {
                                "value": "${testCase.scenario.player2Rating}",
                                "system": "${testCase.scenario.ratingSystem}"
                              }
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {
                                "games": {
                                  "P1": ${testCase.scenario.matchScore.player1Games},
                                  "P2": ${testCase.scenario.matchScore.player2Games}
                                },
                                "winner": "${testCase.winnerId}"
                              }
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                }

            response.status shouldBe HttpStatusCode.OK

            val result = response.body<RankingCalculationResponse>()

            // Validate players are present
            result.players["P1"].shouldNotBe(null)
            result.players["P2"].shouldNotBe(null)

            // Validate ratingChanges exists for both players
            val p1Changes = result.ratingChanges["P1"].shouldNotBeNull()
            val p2Changes = result.ratingChanges["P2"].shouldNotBeNull()

            // Validate previous ratings match input
            p1Changes.previousRating.value shouldBe testCase.scenario.player1Rating
            p2Changes.previousRating.value shouldBe testCase.scenario.player2Rating

            // Validate rating system is preserved
            p1Changes.previousRating.system.name shouldBe testCase.scenario.ratingSystem
            p2Changes.previousRating.system.name shouldBe testCase.scenario.ratingSystem
            p1Changes.newRating.system.name shouldBe testCase.scenario.ratingSystem
            p2Changes.newRating.system.name shouldBe testCase.scenario.ratingSystem

            // Validate new ratings match expected values
            p1Changes.newRating.value shouldBe testCase.expectedPlayer1Rating
            p2Changes.newRating.value shouldBe testCase.expectedPlayer2Rating

            // Note: With the new algorithm, rating changes can be inverted when higher-rated player
            // wins below expectation (e.g., 6-4, 7-5). The winner may actually LOSE rating in these cases.
            // This is intentional behavior to reward competitive performance by the lower-rated player.
            // The expected values already validate the correct behavior, so we don't assert
            // winner always gains and loser always loses.

            // Validate percentChange is calculated correctly
            val p1ExpectedPercent = (p1Changes.change.toDouble() / testCase.scenario.player1Rating.toDouble()) * 100
            val p1ActualPercent = p1Changes.percentChange.toDouble()
            abs(p1ActualPercent - p1ExpectedPercent) shouldBeLessThan 0.01

            val p2ExpectedPercent = (p2Changes.change.toDouble() / testCase.scenario.player2Rating.toDouble()) * 100
            val p2ActualPercent = p2Changes.percentChange.toDouble()
            abs(p2ActualPercent - p2ExpectedPercent) shouldBeLessThan 0.01
        }

    @Test
    fun testValidRankingCalculationWithTiebreak() =
        testApplication {
            application {
                module()
            }

            val client =
                createClient {
                    install(ContentNegotiation) {
                        json(
                            Json {
                                ignoreUnknownKeys = true
                            },
                        )
                    }
                }

            val response =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "players": {
                            "P789": {
                              "playerId": "P789",
                              "name": "Mike Wilson",
                              "rating": {
                                "value": "8.5",
                                "system": "UTR"
                              }
                            },
                            "P101": {
                              "playerId": "P101",
                              "name": "Sarah Lee",
                              "rating": {
                                "value": "8.2",
                                "system": "UTR"
                              }
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {
                                "games": {
                                  "P789": 7,
                                  "P101": 6
                                },
                                "tiebreak": {
                                  "points": {
                                    "P789": 7,
                                    "P101": 5
                                  },
                                  "winner": "P789"
                                },
                                "winner": "P789"
                              }
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                }

            response.status shouldBe HttpStatusCode.OK

            val result = response.body<RankingCalculationResponse>()

            // Validate ratingChanges exists for both players
            val p789Changes = result.ratingChanges["P789"].shouldNotBeNull()
            val p101Changes = result.ratingChanges["P101"].shouldNotBeNull()

            // Validate rating change structure for P789 (winner)
            p789Changes.previousRating.value shouldBe "8.5"
            p789Changes.previousRating.system.name shouldBe "UTR"
            p789Changes.newRating.system.name shouldBe "UTR"

            // Validate rating change structure for P101 (loser)
            p101Changes.previousRating.value shouldBe "8.2"

            // Note: With the new algorithm, rating changes can be inverted when higher-rated player
            // wins below expectation. In this 7-6 tiebreak case, the margin (1 game) is less than
            // expected for a 0.3 rating difference, so changes may be inverted.
            // We validate that changes have opposite signs (zero-sum property with capping)
            val changesHaveOppositeSigns =
                (p789Changes.change.toDouble() > 0.0 && p101Changes.change.toDouble() < 0.0) ||
                    (p789Changes.change.toDouble() < 0.0 && p101Changes.change.toDouble() > 0.0)
            changesHaveOppositeSigns shouldBe true

            // Validate UTR minimum (1.0)
            p789Changes.newRating.value.toDouble() shouldBeGreaterThan (1.0 - 0.001)
            p101Changes.newRating.value.toDouble() shouldBeGreaterThan (1.0 - 0.001)

            // Validate percent changes are consistent with rating changes
            // (can be positive or negative depending on performance vs expectation)
            val p789PercentSign = p789Changes.percentChange.toDouble() > 0.0
            val p789ChangeSign = p789Changes.change.toDouble() > 0.0
            p789PercentSign shouldBe p789ChangeSign

            val p101PercentSign = p101Changes.percentChange.toDouble() > 0.0
            val p101ChangeSign = p101Changes.change.toDouble() > 0.0
            p101PercentSign shouldBe p101ChangeSign
        }

    @Test
    fun testInvalidRating_OutOfRange() =
        testApplication {
            application {
                module()
            }

            val response =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "players": {
                            "P123": {
                              "playerId": "P123",
                              "name": "John Doe",
                              "rating": {
                                "value": 8.5,
                                "system": "NTRP"
                              }
                            },
                            "P456": {
                              "playerId": "P456",
                              "name": "Jane Smith",
                              "rating": {
                                "value": 4.0,
                                "system": "NTRP"
                              }
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {
                                "games": {
                                  "P123": 6,
                                  "P456": 4
                                },
                                "winner": "P123"
                              }
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                }

            val body = response.bodyAsText()
            assertErrorResponse(response.status, body)
        }

    @Test
    fun testValidRating_ContinuousValue() =
        testApplication {
            application {
                module()
            }

            val client =
                createClient {
                    install(ContentNegotiation) {
                        json(
                            Json {
                                ignoreUnknownKeys = true
                            },
                        )
                    }
                }

            val response =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "players": {
                            "P123": {
                              "playerId": "P123",
                              "name": "John Doe",
                              "rating": {
                                "value": "4.3",
                                "system": "NTRP"
                              }
                            },
                            "P456": {
                              "playerId": "P456",
                              "name": "Jane Smith",
                              "rating": {
                                "value": "4.0",
                                "system": "NTRP"
                              }
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {
                                "games": {
                                  "P123": 6,
                                  "P456": 4
                                },
                                "winner": "P123"
                              }
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                }

            // NTRP now supports continuous values, not just 0.5 increments
            response.status shouldBe HttpStatusCode.OK

            val result = response.body<RankingCalculationResponse>()

            // Validate ratingChanges exists and preserves continuous values
            val p123Changes = result.ratingChanges["P123"].shouldNotBeNull()
            p123Changes.previousRating.value shouldBe "4.3"

            // Validate new rating can also be continuous
            val newRating = p123Changes.newRating.value.toDouble()
            newRating shouldBeGreaterThan 4.3

            // Validate rating changes are calculated with precision
            val change = p123Changes.change.toDouble()
            change shouldBeGreaterThan 0.0

            // Validate percent change is present and valid
            val percentChange = p123Changes.percentChange.toDouble()
            percentChange shouldBeGreaterThan 0.0
        }

    @Test
    fun testMismatchedPlayerIds() =
        testApplication {
            application {
                module()
            }

            val response =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "players": {
                            "P123": {
                              "playerId": "P999",
                              "name": "John Doe",
                              "rating": {
                                "value": 4.5,
                                "system": "NTRP"
                              }
                            },
                            "P456": {
                              "playerId": "P456",
                              "name": "Jane Smith",
                              "rating": {
                                "value": 4.0,
                                "system": "NTRP"
                              }
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {
                                "games": {
                                  "P123": 6,
                                  "P456": 4
                                },
                                "winner": "P123"
                              }
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                }

            val body = response.bodyAsText()
            assertErrorResponse(response.status, body)
        }

    @Test
    fun testDifferentRatingSystems() =
        testApplication {
            application {
                module()
            }

            val response =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "players": {
                            "P123": {
                              "playerId": "P123",
                              "name": "John Doe",
                              "rating": {
                                "value": 4.5,
                                "system": "NTRP"
                              }
                            },
                            "P456": {
                              "playerId": "P456",
                              "name": "Jane Smith",
                              "rating": {
                                "value": 8.0,
                                "system": "UTR"
                              }
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {
                                "games": {
                                  "P123": 6,
                                  "P456": 4
                                },
                                "winner": "P123"
                              }
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                }

            val body = response.bodyAsText()
            assertErrorResponse(response.status, body)
        }

    @Test
    fun testInvalidSetScore() =
        testApplication {
            application {
                module()
            }

            val response =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "players": {
                            "P123": {
                              "playerId": "P123",
                              "name": "John Doe",
                              "rating": {
                                "value": 4.5,
                                "system": "NTRP"
                              }
                            },
                            "P456": {
                              "playerId": "P456",
                              "name": "Jane Smith",
                              "rating": {
                                "value": 4.0,
                                "system": "NTRP"
                              }
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {
                                "games": {
                                  "P123": 6,
                                  "P456": 5
                                },
                                "winner": "P123"
                              }
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                }

            val body = response.bodyAsText()
            assertErrorResponse(response.status, body)
        }
}
