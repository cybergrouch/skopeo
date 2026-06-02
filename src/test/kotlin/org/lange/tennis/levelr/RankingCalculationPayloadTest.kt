package org.lange.tennis.levelr

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Snapshot tests that validate exact JSON payloads.
 * These tests ensure the API contract remains stable by comparing
 * exact input/output JSON (minified for comparison).
 */
class RankingCalculationPayloadTest {
    private val json =
        Json {
            prettyPrint = false
            ignoreUnknownKeys = false
        }

    /**
     * Minify JSON by parsing and re-serializing without whitespace.
     * This allows readable multi-line JSON in tests while comparing compact versions.
     */
    private fun minifyJson(jsonString: String): String {
        val element = json.parseToJsonElement(jsonString)
        return json.encodeToString(JsonElement.serializer(), element)
    }

    @Test
    fun testNTRP_BasicMatch_ExactPayload() =
        testApplication {
            application {
                module()
            }

            val requestJson =
                """
                {
                  "players": {
                    "P1": {
                      "playerId": "P1",
                      "name": "Alice",
                      "rating": {
                        "value": "4.5",
                        "system": "NTRP"
                      }
                    },
                    "P2": {
                      "playerId": "P2",
                      "name": "Bob",
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
                          "P1": 6,
                          "P2": 3
                        },
                        "winner": "P1"
                      }
                    ]
                  }
                }
                """

            val response =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody(requestJson.trimIndent())
                }

            assertEquals(expected = HttpStatusCode.OK, actual = response.status)

            val actualJson = response.bodyAsText()

            // Expected response (both players hit boundaries due to high dominance factor)
            val expectedJson =
                """
                {
                  "players": {
                    "P1": {
                      "playerId": "P1",
                      "name": "Alice",
                      "rating": {
                        "value": "7.000000",
                        "system": "NTRP"
                      }
                    },
                    "P2": {
                      "playerId": "P2",
                      "name": "Bob",
                      "rating": {
                        "value": "1.000000",
                        "system": "NTRP"
                      }
                    }
                  },
                  "ratingChanges": {
                    "P1": {
                      "change": "2.500000",
                      "percentChange": "55.555600",
                      "previousRating": {
                        "value": "4.5",
                        "system": "NTRP"
                      },
                      "newRating": {
                        "value": "7.000000",
                        "system": "NTRP"
                      }
                    },
                    "P2": {
                      "change": "-3.000000",
                      "percentChange": "-75.000000",
                      "previousRating": {
                        "value": "4.0",
                        "system": "NTRP"
                      },
                      "newRating": {
                        "value": "1.000000",
                        "system": "NTRP"
                      }
                    }
                  }
                }
                """

            assertEquals(
                expected = minifyJson(expectedJson.trimIndent()),
                actual = minifyJson(actualJson),
                message = "Response payload should match expected JSON exactly",
            )
        }

    @Test
    fun testNTRP_EqualRatings_ExactPayload() =
        testApplication {
            application {
                module()
            }

            val requestJson =
                """
                {
                  "players": {
                    "P1": {
                      "playerId": "P1",
                      "name": "Player One",
                      "rating": {
                        "value": "5.0",
                        "system": "NTRP"
                      }
                    },
                    "P2": {
                      "playerId": "P2",
                      "name": "Player Two",
                      "rating": {
                        "value": "5.0",
                        "system": "NTRP"
                      }
                    }
                  },
                  "matchScore": {
                    "sets": [
                      {
                        "games": {
                          "P1": 6,
                          "P2": 4
                        },
                        "winner": "P1"
                      }
                    ]
                  }
                }
                """

            val response =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody(requestJson.trimIndent())
                }

            assertEquals(expected = HttpStatusCode.OK, actual = response.status)

            val actualJson = response.bodyAsText()

            val expectedJson =
                """
                {
                  "players": {
                    "P1": {
                      "playerId": "P1",
                      "name": "Player One",
                      "rating": {
                        "value": "7.000000",
                        "system": "NTRP"
                      }
                    },
                    "P2": {
                      "playerId": "P2",
                      "name": "Player Two",
                      "rating": {
                        "value": "1.000000",
                        "system": "NTRP"
                      }
                    }
                  },
                  "ratingChanges": {
                    "P1": {
                      "change": "2.000000",
                      "percentChange": "40.000000",
                      "previousRating": {
                        "value": "5.0",
                        "system": "NTRP"
                      },
                      "newRating": {
                        "value": "7.000000",
                        "system": "NTRP"
                      }
                    },
                    "P2": {
                      "change": "-4.000000",
                      "percentChange": "-80.000000",
                      "previousRating": {
                        "value": "5.0",
                        "system": "NTRP"
                      },
                      "newRating": {
                        "value": "1.000000",
                        "system": "NTRP"
                      }
                    }
                  }
                }
                """

            assertEquals(
                expected = minifyJson(expectedJson.trimIndent()),
                actual = minifyJson(actualJson),
                message = "Response payload should match expected JSON exactly",
            )
        }

    @Test
    fun testUTR_BasicMatch_ExactPayload() =
        testApplication {
            application {
                module()
            }

            val requestJson =
                """
                {
                  "players": {
                    "P100": {
                      "playerId": "P100",
                      "name": "Pro Player",
                      "rating": {
                        "value": "12.5",
                        "system": "UTR"
                      }
                    },
                    "P200": {
                      "playerId": "P200",
                      "name": "Amateur",
                      "rating": {
                        "value": "8.0",
                        "system": "UTR"
                      }
                    }
                  },
                  "matchScore": {
                    "sets": [
                      {
                        "games": {
                          "P100": 6,
                          "P200": 2
                        },
                        "winner": "P100"
                      }
                    ]
                  }
                }
                """

            val response =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody(requestJson.trimIndent())
                }

            assertEquals(expected = HttpStatusCode.OK, actual = response.status)

            val actualJson = response.bodyAsText()

            val expectedJson =
                """
                {
                  "players": {
                    "P100": {
                      "playerId": "P100",
                      "name": "Pro Player",
                      "rating": {
                        "value": "51.981920",
                        "system": "UTR"
                      }
                    },
                    "P200": {
                      "playerId": "P200",
                      "name": "Amateur",
                      "rating": {
                        "value": "1.000000",
                        "system": "UTR"
                      }
                    }
                  },
                  "ratingChanges": {
                    "P100": {
                      "change": "39.481920",
                      "percentChange": "315.855400",
                      "previousRating": {
                        "value": "12.5",
                        "system": "UTR"
                      },
                      "newRating": {
                        "value": "51.981920",
                        "system": "UTR"
                      }
                    },
                    "P200": {
                      "change": "-7.000000",
                      "percentChange": "-87.500000",
                      "previousRating": {
                        "value": "8.0",
                        "system": "UTR"
                      },
                      "newRating": {
                        "value": "1.000000",
                        "system": "UTR"
                      }
                    }
                  }
                }
                """

            assertEquals(
                expected = minifyJson(expectedJson.trimIndent()),
                actual = minifyJson(actualJson),
                message = "Response payload should match expected JSON exactly",
            )
        }

    @Test
    fun testNTRP_DominantWin_ExactPayload() =
        testApplication {
            application {
                module()
            }

            val requestJson =
                """
                {
                  "players": {
                    "WINNER": {
                      "playerId": "WINNER",
                      "name": "Dominant Player",
                      "rating": {
                        "value": "4.0",
                        "system": "NTRP"
                      }
                    },
                    "LOSER": {
                      "playerId": "LOSER",
                      "name": "Weaker Player",
                      "rating": {
                        "value": "3.5",
                        "system": "NTRP"
                      }
                    }
                  },
                  "matchScore": {
                    "sets": [
                      {
                        "games": {
                          "WINNER": 6,
                          "LOSER": 0
                        },
                        "winner": "WINNER"
                      },
                      {
                        "games": {
                          "WINNER": 6,
                          "LOSER": 1
                        },
                        "winner": "WINNER"
                      }
                    ]
                  }
                }
                """

            val response =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody(requestJson.trimIndent())
                }

            assertEquals(expected = HttpStatusCode.OK, actual = response.status)

            val actualJson = response.bodyAsText()

            val expectedJson =
                """
                {
                  "players": {
                    "WINNER": {
                      "playerId": "WINNER",
                      "name": "Dominant Player",
                      "rating": {
                        "value": "7.000000",
                        "system": "NTRP"
                      }
                    },
                    "LOSER": {
                      "playerId": "LOSER",
                      "name": "Weaker Player",
                      "rating": {
                        "value": "1.000000",
                        "system": "NTRP"
                      }
                    }
                  },
                  "ratingChanges": {
                    "WINNER": {
                      "change": "3.000000",
                      "percentChange": "75.000000",
                      "previousRating": {
                        "value": "4.0",
                        "system": "NTRP"
                      },
                      "newRating": {
                        "value": "7.000000",
                        "system": "NTRP"
                      }
                    },
                    "LOSER": {
                      "change": "-2.500000",
                      "percentChange": "-71.428600",
                      "previousRating": {
                        "value": "3.5",
                        "system": "NTRP"
                      },
                      "newRating": {
                        "value": "1.000000",
                        "system": "NTRP"
                      }
                    }
                  }
                }
                """

            assertEquals(
                expected = minifyJson(expectedJson.trimIndent()),
                actual = minifyJson(actualJson),
                message = "Response payload should match expected JSON exactly",
            )
        }

    @Test
    fun testNTRP_MinimumBoundary_ExactPayload() =
        testApplication {
            application {
                module()
            }

            val requestJson =
                """
                {
                  "players": {
                    "P1": {
                      "playerId": "P1",
                      "name": "Beginner",
                      "rating": {
                        "value": "1.0",
                        "system": "NTRP"
                      }
                    },
                    "P2": {
                      "playerId": "P2",
                      "name": "Intermediate",
                      "rating": {
                        "value": "3.0",
                        "system": "NTRP"
                      }
                    }
                  },
                  "matchScore": {
                    "sets": [
                      {
                        "games": {
                          "P1": 0,
                          "P2": 6
                        },
                        "winner": "P2"
                      }
                    ]
                  }
                }
                """

            val response =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody(requestJson.trimIndent())
                }

            assertEquals(expected = HttpStatusCode.OK, actual = response.status)

            val actualJson = response.bodyAsText()

            // P1 should be clamped at minimum 1.0, P2 hits maximum 7.0
            val expectedJson =
                """
                {
                  "players": {
                    "P1": {
                      "playerId": "P1",
                      "name": "Beginner",
                      "rating": {
                        "value": "1.000000",
                        "system": "NTRP"
                      }
                    },
                    "P2": {
                      "playerId": "P2",
                      "name": "Intermediate",
                      "rating": {
                        "value": "7.000000",
                        "system": "NTRP"
                      }
                    }
                  },
                  "ratingChanges": {
                    "P1": {
                      "change": "0.000000",
                      "percentChange": "0.000000",
                      "previousRating": {
                        "value": "1.0",
                        "system": "NTRP"
                      },
                      "newRating": {
                        "value": "1.000000",
                        "system": "NTRP"
                      }
                    },
                    "P2": {
                      "change": "4.000000",
                      "percentChange": "133.333300",
                      "previousRating": {
                        "value": "3.0",
                        "system": "NTRP"
                      },
                      "newRating": {
                        "value": "7.000000",
                        "system": "NTRP"
                      }
                    }
                  }
                }
                """

            assertEquals(
                expected = minifyJson(expectedJson.trimIndent()),
                actual = minifyJson(actualJson),
                message = "Response payload should match expected JSON exactly (with minimum clamping)",
            )
        }

    @Test
    fun testNTRP_MaximumBoundary_ExactPayload() =
        testApplication {
            application {
                module()
            }

            val requestJson =
                """
                {
                  "players": {
                    "P1": {
                      "playerId": "P1",
                      "name": "Expert",
                      "rating": {
                        "value": "7.0",
                        "system": "NTRP"
                      }
                    },
                    "P2": {
                      "playerId": "P2",
                      "name": "Advanced",
                      "rating": {
                        "value": "6.0",
                        "system": "NTRP"
                      }
                    }
                  },
                  "matchScore": {
                    "sets": [
                      {
                        "games": {
                          "P1": 6,
                          "P2": 0
                        },
                        "winner": "P1"
                      }
                    ]
                  }
                }
                """

            val response =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody(requestJson.trimIndent())
                }

            assertEquals(expected = HttpStatusCode.OK, actual = response.status)

            val actualJson = response.bodyAsText()

            // P1 should be clamped at maximum 7.0, P2 hits minimum 1.0
            val expectedJson =
                """
                {
                  "players": {
                    "P1": {
                      "playerId": "P1",
                      "name": "Expert",
                      "rating": {
                        "value": "7.000000",
                        "system": "NTRP"
                      }
                    },
                    "P2": {
                      "playerId": "P2",
                      "name": "Advanced",
                      "rating": {
                        "value": "1.000000",
                        "system": "NTRP"
                      }
                    }
                  },
                  "ratingChanges": {
                    "P1": {
                      "change": "0.000000",
                      "percentChange": "0.000000",
                      "previousRating": {
                        "value": "7.0",
                        "system": "NTRP"
                      },
                      "newRating": {
                        "value": "7.000000",
                        "system": "NTRP"
                      }
                    },
                    "P2": {
                      "change": "-5.000000",
                      "percentChange": "-83.333300",
                      "previousRating": {
                        "value": "6.0",
                        "system": "NTRP"
                      },
                      "newRating": {
                        "value": "1.000000",
                        "system": "NTRP"
                      }
                    }
                  }
                }
                """

            assertEquals(
                expected = minifyJson(expectedJson.trimIndent()),
                actual = minifyJson(actualJson),
                message = "Response payload should match expected JSON exactly (with maximum clamping)",
            )
        }

    @Test
    fun testUTR_Tiebreak_ExactPayload() =
        testApplication {
            application {
                module()
            }

            val requestJson =
                """
                {
                  "players": {
                    "PA": {
                      "playerId": "PA",
                      "name": "Alex",
                      "rating": {
                        "value": "10.0",
                        "system": "UTR"
                      }
                    },
                    "PB": {
                      "playerId": "PB",
                      "name": "Blake",
                      "rating": {
                        "value": "9.8",
                        "system": "UTR"
                      }
                    }
                  },
                  "matchScore": {
                    "sets": [
                      {
                        "games": {
                          "PA": 7,
                          "PB": 6
                        },
                        "tiebreak": {
                          "points": {
                            "PA": 7,
                            "PB": 5
                          },
                          "winner": "PA"
                        },
                        "winner": "PA"
                      }
                    ]
                  }
                }
                """

            val response =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody(requestJson.trimIndent())
                }

            assertEquals(expected = HttpStatusCode.OK, actual = response.status)

            val actualJson = response.bodyAsText()

            val expectedJson =
                """
                {
                  "players": {
                    "PA": {
                      "playerId": "PA",
                      "name": "Alex",
                      "rating": {
                        "value": "28.655920",
                        "system": "UTR"
                      }
                    },
                    "PB": {
                      "playerId": "PB",
                      "name": "Blake",
                      "rating": {
                        "value": "1.000000",
                        "system": "UTR"
                      }
                    }
                  },
                  "ratingChanges": {
                    "PA": {
                      "change": "18.655920",
                      "percentChange": "186.559200",
                      "previousRating": {
                        "value": "10.0",
                        "system": "UTR"
                      },
                      "newRating": {
                        "value": "28.655920",
                        "system": "UTR"
                      }
                    },
                    "PB": {
                      "change": "-8.800000",
                      "percentChange": "-89.795900",
                      "previousRating": {
                        "value": "9.8",
                        "system": "UTR"
                      },
                      "newRating": {
                        "value": "1.000000",
                        "system": "UTR"
                      }
                    }
                  }
                }
                """

            assertEquals(
                expected = minifyJson(expectedJson.trimIndent()),
                actual = minifyJson(actualJson),
                message = "Response payload should match expected JSON exactly",
            )
        }
}
