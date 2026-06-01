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

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"playerId\":\"P123\""))
            assertTrue(body.contains("\"playerId\":\"P456\""))
            assertTrue(body.contains("ratingChanges"))
        }

    @Test
    fun testValidRankingCalculationWithTiebreak() =
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
                            "P789": {
                              "playerId": "P789",
                              "name": "Mike Wilson",
                              "rating": {
                                "value": 8.5,
                                "system": "UTR"
                              }
                            },
                            "P101": {
                              "playerId": "P101",
                              "name": "Sarah Lee",
                              "rating": {
                                "value": 8.2,
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

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"playerId\":\"P789\""))
            assertTrue(body.contains("\"playerId\":\"P101\""))
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
    fun testInvalidRating_WrongIncrement() =
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
                                "value": 4.3,
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
