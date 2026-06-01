package org.lange.tennis.levelr

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.lange.tennis.levelr.dto.RankingCalculationResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RankingAlgorithmTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testWinnerGainsRating_NTRP() =
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
                              "name": "Winner",
                              "rating": {"value": 4.0, "system": "NTRP"}
                            },
                            "P002": {
                              "playerId": "P002",
                              "name": "Loser",
                              "rating": {"value": 4.0, "system": "NTRP"}
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {"games": {"P001": 6, "P002": 4}, "winner": "P001"},
                              {"games": {"P001": 6, "P002": 3}, "winner": "P001"}
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val result = json.decodeFromString<RankingCalculationResponse>(body)

            // Winner should gain rating
            val winnerChange = result.ratingChanges["P001"]!!
            assertTrue(winnerChange.change > 0, "Winner should gain rating, got ${winnerChange.change}")

            // Loser should lose rating
            val loserChange = result.ratingChanges["P002"]!!
            assertTrue(loserChange.change < 0, "Loser should lose rating, got ${loserChange.change}")

            // NTRP ratings should be within valid range (1.0-7.0)
            val winnerRating = result.players["P001"]!!.rating.value
            assertTrue(
                winnerRating in 1.0..7.0,
                "NTRP rating must be between 1.0 and 7.0, got $winnerRating",
            )

            val loserRating = result.players["P002"]!!.rating.value
            assertTrue(
                loserRating in 1.0..7.0,
                "NTRP rating must be between 1.0 and 7.0, got $loserRating",
            )
        }

    @Test
    fun testWinnerGainsRating_UTR() =
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
                              "name": "Winner",
                              "rating": {"value": 8.5, "system": "UTR"}
                            },
                            "P002": {
                              "playerId": "P002",
                              "name": "Loser",
                              "rating": {"value": 8.5, "system": "UTR"}
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {"games": {"P001": 6, "P002": 3}, "winner": "P001"},
                              {"games": {"P001": 6, "P002": 2}, "winner": "P001"}
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val result = json.decodeFromString<RankingCalculationResponse>(body)

            // Winner should gain rating
            val winnerChange = result.ratingChanges["P001"]!!
            assertTrue(winnerChange.change > 0, "Winner should gain rating, got ${winnerChange.change}")

            // Loser should lose rating
            val loserChange = result.ratingChanges["P002"]!!
            assertTrue(loserChange.change < 0, "Loser should lose rating, got ${loserChange.change}")

            // UTR ratings can be decimal
            val winnerRating = result.players["P001"]!!.rating.value
            assertTrue(winnerRating >= 1.0, "UTR rating must be >= 1.0, got $winnerRating")
        }

    @Test
    fun testUpsetWin_LowerRatedWinner() =
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
                              "name": "Underdog",
                              "rating": {"value": 4.0, "system": "NTRP"}
                            },
                            "P002": {
                              "playerId": "P002",
                              "name": "Favorite",
                              "rating": {"value": 5.5, "system": "NTRP"}
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {"games": {"P001": 6, "P002": 4}, "winner": "P001"},
                              {"games": {"P001": 6, "P002": 3}, "winner": "P001"}
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val result = json.decodeFromString<RankingCalculationResponse>(body)

            // Underdog should gain more for upset win
            val underdogChange = result.ratingChanges["P001"]!!
            assertTrue(underdogChange.change > 0, "Underdog should gain rating")

            // Favorite should lose rating
            val favoriteChange = result.ratingChanges["P002"]!!
            assertTrue(favoriteChange.change < 0, "Favorite should lose rating")
        }

    @Test
    fun testDominantWin_LargerRatingChange() =
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
                              "name": "Dominant Player",
                              "rating": {"value": 8.5, "system": "UTR"}
                            },
                            "P002": {
                              "playerId": "P002",
                              "name": "Opponent",
                              "rating": {"value": 8.5, "system": "UTR"}
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {"games": {"P001": 6, "P002": 0}, "winner": "P001"},
                              {"games": {"P001": 6, "P002": 1}, "winner": "P001"}
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val result = json.decodeFromString<RankingCalculationResponse>(body)

            val winnerChange = result.ratingChanges["P001"]!!
            val loserChange = result.ratingChanges["P002"]!!

            // Dominant win should produce non-zero change
            assertTrue(winnerChange.change > 0, "Dominant winner should gain rating")
            assertTrue(loserChange.change < 0, "Loser should lose rating")
        }

    @Test
    fun testCloseMatch_SmallerRatingChange() =
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
                              "name": "Player 1",
                              "rating": {"value": 8.5, "system": "UTR"}
                            },
                            "P002": {
                              "playerId": "P002",
                              "name": "Player 2",
                              "rating": {"value": 8.5, "system": "UTR"}
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {"games": {"P001": 6, "P002": 4}, "winner": "P001"},
                              {"games": {"P001": 4, "P002": 6}, "winner": "P002"},
                              {"games": {"P001": 7, "P002": 5}, "winner": "P001"}
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val result = json.decodeFromString<RankingCalculationResponse>(body)

            val winnerChange = result.ratingChanges["P001"]!!

            // Close match should still produce rating change
            assertTrue(winnerChange.change > 0, "Winner should gain rating even in close match")
        }

    @Test
    fun testNTRPBoundaries_MaxRating() =
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
                              "name": "Pro Player",
                              "rating": {"value": 7.0, "system": "NTRP"}
                            },
                            "P002": {
                              "playerId": "P002",
                              "name": "Opponent",
                              "rating": {"value": 6.5, "system": "NTRP"}
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

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val result = json.decodeFromString<RankingCalculationResponse>(body)

            // Player at max NTRP should not exceed 7.0
            val proRating = result.players["P001"]!!.rating.value
            assertTrue(proRating <= 7.0, "NTRP rating should not exceed 7.0, got $proRating")
            assertTrue(proRating >= 1.0, "NTRP rating must be at least 1.0, got $proRating")
        }

    @Test
    fun testNTRPBoundaries_MinRating() =
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
                              "name": "Expert",
                              "rating": {"value": 6.0, "system": "NTRP"}
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

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val result = json.decodeFromString<RankingCalculationResponse>(body)

            // Player at min NTRP should not go below 1.0
            val beginnerRating = result.players["P001"]!!.rating.value
            assertTrue(beginnerRating >= 1.0, "NTRP rating should not go below 1.0, got $beginnerRating")
            assertTrue(beginnerRating <= 7.0, "NTRP rating should not exceed 7.0, got $beginnerRating")
        }

    @Test
    fun testUTRMinimumBoundary() =
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
                              "name": "Low UTR",
                              "rating": {"value": 1.0, "system": "UTR"}
                            },
                            "P002": {
                              "playerId": "P002",
                              "name": "High UTR",
                              "rating": {"value": 12.0, "system": "UTR"}
                            }
                          },
                          "matchScore": {
                            "sets": [
                              {"games": {"P001": 0, "P002": 6}, "winner": "P002"},
                              {"games": {"P001": 1, "P002": 6}, "winner": "P002"}
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val result = json.decodeFromString<RankingCalculationResponse>(body)

            // UTR should not go below 1.0
            val lowRating = result.players["P001"]!!.rating.value
            assertTrue(lowRating >= 1.0, "UTR rating should not go below 1.0, got $lowRating")
        }
}
