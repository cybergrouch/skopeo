package org.lange.tennis.levelr

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun testThreeSetMatch() =
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
                              "rating": {"value": 5.0, "system": "NTRP"}
                            },
                            "P002": {
                              "playerId": "P002",
                              "name": "Bob",
                              "rating": {"value": 5.5, "system": "NTRP"}
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {"games": {"P001": 6, "P002": 4}, "winner": "P001"},
                              {"games": {"P001": 3, "P002": 6}, "winner": "P002"},
                              {"games": {"P001": 6, "P002": 2}, "winner": "P001"}
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("P001"))
            assertTrue(body.contains("P002"))
        }

    @Test
    fun testMinimumRatings() =
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
                              "name": "Beginner",
                              "rating": {"value": 1.0, "system": "NTRP"}
                            },
                            "P002": {
                              "playerId": "P002",
                              "name": "Intermediate",
                              "rating": {"value": 3.0, "system": "NTRP"}
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

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun testMaximumNTRPRating() =
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
                              "name": "Pro",
                              "rating": {"value": 7.0, "system": "NTRP"}
                            },
                            "P002": {
                              "playerId": "P002",
                              "name": "Advanced",
                              "rating": {"value": 6.5, "system": "NTRP"}
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

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun testHighUTRRatings() =
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
                              "name": "Professional Player",
                              "rating": {"value": 15.5, "system": "UTR"}
                            },
                            "P002": {
                              "playerId": "P002",
                              "name": "Top Collegiate",
                              "rating": {"value": 13.2, "system": "UTR"}
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

            assertEquals(HttpStatusCode.OK, response.status)
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
                              {"games": {"P001": 6, "P002": 4}, "winner": "P001"}
                            ]
                          },
                          "matchDate": "2024-01-15T14:30:00Z"
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }
}
