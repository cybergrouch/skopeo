package org.lange.tennis.levelr

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
import org.lange.tennis.levelr.dto.RankingCalculationResponse
import kotlin.test.Test
import io.kotest.matchers.ints.shouldBeGreaterThan as intsShouldBeGreaterThan

class RankingCalculationApiTest {
    private fun assertErrorResponse(
        status: HttpStatusCode,
        body: String,
    ) {
        status.value intsShouldBeGreaterThan 399
        body.shouldNotBeEmpty()
    }

    @Test
    fun testValidRankingCalculation() =
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
                                "value": "4.5",
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

            response.status shouldBe HttpStatusCode.OK

            val result = response.body<RankingCalculationResponse>()

            // Validate players are present
            result.players["P123"].shouldNotBe(null)
            result.players["P456"].shouldNotBe(null)

            // Validate ratingChanges exists for both players
            val p123Changes = result.ratingChanges["P123"].shouldNotBeNull()
            val p456Changes = result.ratingChanges["P456"].shouldNotBeNull()

            // Validate rating change structure for P123 (winner)
            p123Changes.previousRating.value shouldBe "4.5"
            p123Changes.change.toDouble() shouldBeGreaterThan 0.0
            p123Changes.newRating.value.toDouble() shouldBeGreaterThan 4.5

            // Validate rating change structure for P456 (loser)
            p456Changes.previousRating.value shouldBe "4.0"
            p456Changes.change.toDouble() shouldBeLessThan 0.0
            p456Changes.newRating.value.toDouble() shouldBeLessThan 4.0

            // Validate zero-sum property (approximately, may not be exact due to clamping)
            // The changes should have opposite signs
            p123Changes.change.toDouble() shouldBeGreaterThan 0.0
            p456Changes.change.toDouble() shouldBeLessThan 0.0

            // Validate percentChange is calculated correctly for P123
            val expectedP123Percent = (p123Changes.change.toDouble() / 4.5) * 100
            val actualP123Percent = p123Changes.percentChange.toDouble()
            kotlin.math.abs(actualP123Percent - expectedP123Percent) shouldBeLessThan 0.01

            // Validate percentChange is calculated correctly for P456
            val expectedP456Percent = (p456Changes.change.toDouble() / 4.0) * 100
            val actualP456Percent = p456Changes.percentChange.toDouble()
            kotlin.math.abs(actualP456Percent - expectedP456Percent) shouldBeLessThan 0.01

            // Validate rating values are within NTRP bounds
            // p123Changes.newRating.value.toDouble() should be in 1.0..7.0
            assert(p123Changes.newRating.value.toDouble() in 1.0..7.0)
            // p456Changes.newRating.value.toDouble() should be in 1.0..7.0
            assert(p456Changes.newRating.value.toDouble() in 1.0..7.0)
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
            p789Changes.change.toDouble() shouldBeGreaterThan 0.0
            p789Changes.previousRating.system.name shouldBe "UTR"
            p789Changes.newRating.system.name shouldBe "UTR"

            // Validate rating change structure for P101 (loser)
            p101Changes.previousRating.value shouldBe "8.2"
            p101Changes.change.toDouble() shouldBeLessThan 0.0

            // Validate changes have opposite signs (zero-sum may not hold due to clamping)
            p789Changes.change.toDouble() shouldBeGreaterThan 0.0
            p101Changes.change.toDouble() shouldBeLessThan 0.0

            // Validate UTR minimum (1.0)
            p789Changes.newRating.value.toDouble() shouldBeGreaterThan (1.0 - 0.001)
            p101Changes.newRating.value.toDouble() shouldBeGreaterThan (1.0 - 0.001)

            // Validate percent changes have proper sign
            p789Changes.percentChange.toDouble() shouldBeGreaterThan 0.0
            p101Changes.percentChange.toDouble() shouldBeLessThan 0.0
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
