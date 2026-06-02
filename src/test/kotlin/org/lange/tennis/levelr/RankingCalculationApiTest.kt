package org.lange.tennis.levelr

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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RankingCalculationApiTest {
    private fun assertErrorResponse(
        status: HttpStatusCode,
        body: String,
    ) {
        assertTrue(status.value >= 400, "Expected error status (>=400) but got $status")
        assertTrue(body.isNotEmpty(), "Expected non-empty response body but got empty")
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

            assertEquals(expected = HttpStatusCode.OK, actual = response.status)

            val result = response.body<RankingCalculationResponse>()

            // Validate players are present
            assertNotNull(actual = result.players["P123"])
            assertNotNull(actual = result.players["P456"])

            // Validate ratingChanges exists for both players
            val p123Changes = result.ratingChanges["P123"]
            val p456Changes = result.ratingChanges["P456"]
            assertNotNull(actual = p123Changes, message = "P123 should have rating changes")
            assertNotNull(actual = p456Changes, message = "P456 should have rating changes")

            // Validate rating change structure for P123 (winner)
            assertEquals(expected = "4.5", actual = p123Changes.previousRating.value, message = "P123 previous rating should match input")
            assertTrue(
                actual = p123Changes.change.toDouble() > 0,
                message = "Winner should gain rating, got ${p123Changes.change}",
            )
            assertTrue(
                actual = p123Changes.newRating.value.toDouble() > 4.5,
                message = "Winner's new rating should be higher than previous",
            )

            // Validate rating change structure for P456 (loser)
            assertEquals(expected = "4.0", actual = p456Changes.previousRating.value, message = "P456 previous rating should match input")
            assertTrue(
                actual = p456Changes.change.toDouble() < 0,
                message = "Loser should lose rating, got ${p456Changes.change}",
            )
            assertTrue(
                actual = p456Changes.newRating.value.toDouble() < 4.0,
                message = "Loser's new rating should be lower than previous",
            )

            // Validate zero-sum property (approximately, may not be exact due to clamping)
            // The changes should have opposite signs
            assertTrue(
                actual = p123Changes.change.toDouble() > 0,
                message = "Winner's change should be positive",
            )
            assertTrue(
                actual = p456Changes.change.toDouble() < 0,
                message = "Loser's change should be negative",
            )

            // Validate percentChange is calculated correctly for P123
            val expectedP123Percent = (p123Changes.change.toDouble() / 4.5) * 100
            val actualP123Percent = p123Changes.percentChange.toDouble()
            assertEquals(
                expected = expectedP123Percent,
                actual = actualP123Percent,
                absoluteTolerance = 0.01,
                message = "P123 percent change should be calculated correctly",
            )

            // Validate percentChange is calculated correctly for P456
            val expectedP456Percent = (p456Changes.change.toDouble() / 4.0) * 100
            val actualP456Percent = p456Changes.percentChange.toDouble()
            assertEquals(
                expected = expectedP456Percent,
                actual = actualP456Percent,
                absoluteTolerance = 0.01,
                message = "P456 percent change should be calculated correctly",
            )

            // Validate rating values are within NTRP bounds
            assertTrue(
                actual = p123Changes.newRating.value.toDouble() in 1.0..7.0,
                message = "NTRP rating should be within 1.0-7.0 range",
            )
            assertTrue(
                actual = p456Changes.newRating.value.toDouble() in 1.0..7.0,
                message = "NTRP rating should be within 1.0-7.0 range",
            )
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

            assertEquals(expected = HttpStatusCode.OK, actual = response.status)

            val result = response.body<RankingCalculationResponse>()

            // Validate ratingChanges exists for both players
            val p789Changes = result.ratingChanges["P789"]
            val p101Changes = result.ratingChanges["P101"]
            assertNotNull(actual = p789Changes, message = "P789 should have rating changes")
            assertNotNull(actual = p101Changes, message = "P101 should have rating changes")

            // Validate rating change structure for P789 (winner)
            assertEquals(expected = "8.5", actual = p789Changes.previousRating.value, message = "P789 previous rating should match input")
            assertTrue(
                actual = p789Changes.change.toDouble() > 0,
                message = "Winner should gain rating, got ${p789Changes.change}",
            )
            assertEquals(expected = "UTR", actual = p789Changes.previousRating.system.name)
            assertEquals(expected = "UTR", actual = p789Changes.newRating.system.name)

            // Validate rating change structure for P101 (loser)
            assertEquals(expected = "8.2", actual = p101Changes.previousRating.value, message = "P101 previous rating should match input")
            assertTrue(
                actual = p101Changes.change.toDouble() < 0,
                message = "Loser should lose rating, got ${p101Changes.change}",
            )

            // Validate changes have opposite signs (zero-sum may not hold due to clamping)
            assertTrue(
                actual = p789Changes.change.toDouble() > 0,
                message = "Winner's change should be positive",
            )
            assertTrue(
                actual = p101Changes.change.toDouble() < 0,
                message = "Loser's change should be negative",
            )

            // Validate UTR minimum (1.0)
            assertTrue(
                actual = p789Changes.newRating.value.toDouble() >= 1.0,
                message = "UTR rating should be at least 1.0",
            )
            assertTrue(
                actual = p101Changes.newRating.value.toDouble() >= 1.0,
                message = "UTR rating should be at least 1.0",
            )

            // Validate percent changes have proper sign
            assertTrue(
                actual = p789Changes.percentChange.toDouble() > 0,
                message = "Winner's percent change should be positive",
            )
            assertTrue(
                actual = p101Changes.percentChange.toDouble() < 0,
                message = "Loser's percent change should be negative",
            )
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
            assertEquals(expected = HttpStatusCode.OK, actual = response.status)

            val result = response.body<RankingCalculationResponse>()

            // Validate ratingChanges exists and preserves continuous values
            val p123Changes = result.ratingChanges["P123"]
            assertNotNull(actual = p123Changes)
            assertEquals(
                expected = "4.3",
                actual = p123Changes.previousRating.value,
                message = "Continuous rating value should be preserved",
            )

            // Validate new rating can also be continuous
            val newRating = p123Changes.newRating.value.toDouble()
            assertTrue(
                actual = newRating > 4.3,
                message = "Winner's continuous rating should increase",
            )

            // Validate rating changes are calculated with precision
            val change = p123Changes.change.toDouble()
            assertTrue(actual = change > 0, message = "Rating change should be positive for winner")

            // Validate percent change is present and valid
            val percentChange = p123Changes.percentChange.toDouble()
            assertTrue(actual = percentChange > 0, message = "Percent change should be positive for winner")
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
