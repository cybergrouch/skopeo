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

class RankingCalculationExtendedTest {
    private fun assertErrorResponse(
        status: HttpStatusCode,
        body: String,
    ) {
        assertTrue(status.value >= 400, "Expected error status (>=400) but got $status")
        assertTrue(body.isNotEmpty(), "Expected non-empty response body")
    }

    @Test
    fun testEmptyPlayerName() =
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
                            "P001": {
                              "playerId": "P001",
                              "name": "",
                              "rating": {"value": 4.0, "system": "NTRP"}
                            },
                            "P002": {
                              "playerId": "P002",
                              "name": "Bob",
                              "rating": {"value": 4.5, "system": "NTRP"}
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {"games": {"P001": 6, "P002": 4}, "winner": "P001"}
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
    fun testEmptyPlayerId() =
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
                            "": {
                              "playerId": "",
                              "name": "Alice",
                              "rating": {"value": 4.0, "system": "NTRP"}
                            },
                            "P002": {
                              "playerId": "P002",
                              "name": "Bob",
                              "rating": {"value": 4.5, "system": "NTRP"}
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {"games": {"": 6, "P002": 4}, "winner": ""}
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
    fun testTooManyPlayers() =
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
                            "P001": {
                              "playerId": "P001",
                              "name": "Alice",
                              "rating": {"value": 4.0, "system": "NTRP"}
                            },
                            "P002": {
                              "playerId": "P002",
                              "name": "Bob",
                              "rating": {"value": 4.5, "system": "NTRP"}
                            },
                            "P003": {
                              "playerId": "P003",
                              "name": "Charlie",
                              "rating": {"value": 5.0, "system": "NTRP"}
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {"games": {"P001": 6, "P002": 4}, "winner": "P001"}
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
    fun testTooFewPlayers() =
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
                            "P001": {
                              "playerId": "P001",
                              "name": "Alice",
                              "rating": {"value": 4.0, "system": "NTRP"}
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {"games": {"P001": 6, "P002": 4}, "winner": "P001"}
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
    fun testInvalidWinner() =
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
                            "P001": {
                              "playerId": "P001",
                              "name": "Alice",
                              "rating": {"value": 4.0, "system": "NTRP"}
                            },
                            "P002": {
                              "playerId": "P002",
                              "name": "Bob",
                              "rating": {"value": 4.5, "system": "NTRP"}
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {"games": {"P001": 6, "P002": 4}, "winner": "P999"}
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
    fun testMatchWithDate() =
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
                            "P001": {
                              "playerId": "P001",
                              "name": "Alice",
                              "rating": {"value": "4.0", "system": "NTRP"}
                            },
                            "P002": {
                              "playerId": "P002",
                              "name": "Bob",
                              "rating": {"value": "4.5", "system": "NTRP"}
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {"games": {"P001": 6, "P002": 4}, "winner": "P001"}
                            ]
                          },
                          "matchDate": "2024-01-15T14:30:00Z"
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(expected = HttpStatusCode.OK, actual = response.status)

            val result = response.body<RankingCalculationResponse>()

            // Validate ratingChanges are calculated correctly even with matchDate
            val p001Changes = result.ratingChanges["P001"]
            val p002Changes = result.ratingChanges["P002"]
            assertNotNull(actual = p001Changes, message = "P001 should have rating changes")
            assertNotNull(actual = p002Changes, message = "P002 should have rating changes")

            // Validate P001 (underdog winner) gains significant rating
            assertEquals(expected = "4.0", actual = p001Changes.previousRating.value)
            assertTrue(
                actual = p001Changes.change.toDouble() > 0,
                message = "Underdog winner should gain rating",
            )

            // Validate P002 (favorite loser) loses rating
            assertEquals(expected = "4.5", actual = p002Changes.previousRating.value)
            assertTrue(
                actual = p002Changes.change.toDouble() < 0,
                message = "Favorite loser should lose rating",
            )

            // Validate all required fields are present
            assertNotNull(actual = p001Changes.change)
            assertNotNull(actual = p001Changes.percentChange)
            assertNotNull(actual = p001Changes.newRating)
            assertNotNull(actual = p002Changes.change)
            assertNotNull(actual = p002Changes.percentChange)
            assertNotNull(actual = p002Changes.newRating)
        }

    @Test
    fun testRatingChanges_MinimumBoundary() =
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
                            "P001": {
                              "playerId": "P001",
                              "name": "Novice",
                              "rating": {"value": "1.0", "system": "NTRP"}
                            },
                            "P002": {
                              "playerId": "P002",
                              "name": "Expert",
                              "rating": {"value": "6.0", "system": "NTRP"}
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {"games": {"P001": 0, "P002": 6}, "winner": "P002"},
                              {"games": {"P001": 0, "P002": 6}, "winner": "P002"}
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(expected = HttpStatusCode.OK, actual = response.status)

            val result = response.body<RankingCalculationResponse>()
            val p001Changes = result.ratingChanges["P001"]
            assertNotNull(actual = p001Changes)

            // Validate that rating doesn't go below minimum (1.0 for NTRP)
            val newRating = p001Changes.newRating.value.toDouble()
            assertTrue(
                actual = newRating >= 1.0,
                message = "NTRP rating should not go below 1.0, got $newRating",
            )

            // Validate clamping is reflected in the change
            val previousRating = p001Changes.previousRating.value.toDouble()
            val actualChange = newRating - previousRating
            assertEquals(
                expected = actualChange,
                actual = p001Changes.change.toDouble(),
                absoluteTolerance = 0.0001,
                message = "Change should reflect clamping",
            )
        }

    @Test
    fun testRatingChanges_DominantVsCloseMatch() =
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

            // Close match (6-4, 6-4) - relatively close
            val closeMatchResponse =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "players": {
                            "P001": {"playerId": "P001", "name": "Alice", "rating": {"value": "3.5", "system": "NTRP"}},
                            "P002": {"playerId": "P002", "name": "Bob", "rating": {"value": "3.5", "system": "NTRP"}}
                          },
                          "matchScore": {
                            "sets": [
                              {"games": {"P001": 6, "P002": 4}, "winner": "P001"},
                              {"games": {"P001": 6, "P002": 4}, "winner": "P001"}
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(expected = HttpStatusCode.OK, actual = closeMatchResponse.status, message = "Close match response should be OK")
            val closeResult = closeMatchResponse.body<RankingCalculationResponse>()
            val closeWinnerChange = closeResult.ratingChanges["P001"]!!.change.toDouble()

            // Dominant match (6-2, 6-1) - more decisive win
            val dominantMatchResponse =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "players": {
                            "P003": {"playerId": "P003", "name": "Charlie", "rating": {"value": "3.5", "system": "NTRP"}},
                            "P004": {"playerId": "P004", "name": "Diana", "rating": {"value": "3.5", "system": "NTRP"}}
                          },
                          "matchScore": {
                            "sets": [
                              {"games": {"P003": 6, "P004": 2}, "winner": "P003"},
                              {"games": {"P003": 6, "P004": 1}, "winner": "P003"}
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(
                expected = HttpStatusCode.OK,
                actual = dominantMatchResponse.status,
                message = "Dominant match response should be OK",
            )
            val dominantResult = dominantMatchResponse.body<RankingCalculationResponse>()
            val dominantWinnerChange = dominantResult.ratingChanges["P003"]!!.change.toDouble()

            // Validate both winners gained rating
            assertTrue(
                actual = closeWinnerChange > 0,
                message = "Close match winner should gain rating, got $closeWinnerChange",
            )
            assertTrue(
                actual = dominantWinnerChange > 0,
                message = "Dominant match winner should gain rating, got $dominantWinnerChange",
            )

            // Validate that dominant win produces larger or equal rating change
            // (may be equal if both hit boundary conditions, but dominant should never be less)
            assertTrue(
                actual = dominantWinnerChange >= closeWinnerChange,
                message =
                    "Dominant win should produce >= rating change than close win. " +
                        "Dominant: $dominantWinnerChange, Close: $closeWinnerChange",
            )
        }

    @Test
    fun testRatingChanges_MaximumBoundary() =
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
                            "P001": {
                              "playerId": "P001",
                              "name": "Expert",
                              "rating": {"value": "7.0", "system": "NTRP"}
                            },
                            "P002": {
                              "playerId": "P002",
                              "name": "Advanced",
                              "rating": {"value": "6.0", "system": "NTRP"}
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {"games": {"P001": 6, "P002": 0}, "winner": "P001"},
                              {"games": {"P001": 6, "P002": 0}, "winner": "P001"}
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(expected = HttpStatusCode.OK, actual = response.status)

            val result = response.body<RankingCalculationResponse>()
            val p001Changes = result.ratingChanges["P001"]
            assertNotNull(actual = p001Changes)

            // Validate that rating doesn't exceed maximum (7.0 for NTRP)
            val newRating = p001Changes.newRating.value.toDouble()
            assertTrue(
                actual = newRating <= 7.0,
                message = "NTRP rating should not exceed 7.0, got $newRating",
            )

            // Since player is at max, change might be clamped to 0
            val previousRating = p001Changes.previousRating.value.toDouble()
            val actualChange = newRating - previousRating
            assertEquals(
                expected = actualChange,
                actual = p001Changes.change.toDouble(),
                absoluteTolerance = 0.0001,
                message = "Change should reflect clamping at maximum",
            )
        }

    @Test
    fun testRatingChanges_PrecisionValidation() =
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
                            "P001": {"playerId": "P001", "name": "Alice", "rating": {"value": "4.123456", "system": "NTRP"}},
                            "P002": {"playerId": "P002", "name": "Bob", "rating": {"value": "4.654321", "system": "NTRP"}}
                          },
                          "matchScore": {
                            "sets": [
                              {"games": {"P001": 6, "P002": 4}, "winner": "P001"}
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(expected = HttpStatusCode.OK, actual = response.status)

            val result = response.body<RankingCalculationResponse>()
            val p001Changes = result.ratingChanges["P001"]
            assertNotNull(actual = p001Changes)

            // Validate that values maintain precision (up to 6 decimal places)
            val changeValue = p001Changes.change
            val percentValue = p001Changes.percentChange
            val newRatingValue = p001Changes.newRating.value

            // Validate values are non-empty strings
            assertTrue(actual = changeValue.isNotEmpty(), message = "Change should not be empty")
            assertTrue(actual = percentValue.isNotEmpty(), message = "Percent change should not be empty")
            assertTrue(actual = newRatingValue.isNotEmpty(), message = "New rating should not be empty")

            // Validate values can be parsed as numbers
            val changeDouble = changeValue.toDouble()
            val percentDouble = percentValue.toDouble()
            val newRatingDouble = newRatingValue.toDouble()

            assertTrue(actual = changeDouble > 0, message = "Winner's change should be positive")
            assertTrue(actual = percentDouble > 0, message = "Winner's percent change should be positive")
            assertTrue(actual = newRatingDouble > 4.123456, message = "Winner's new rating should be higher")

            // Validate precision: check that values don't have more than 6 decimal places
            val changeDecimals = if (changeValue.contains(".")) changeValue.split(".")[1].length else 0
            assertTrue(
                actual = changeDecimals <= 6,
                message = "Change should have at most 6 decimal places, got $changeDecimals",
            )
        }
}
