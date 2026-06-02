package org.lange.tennis.levelr.service.calculator

import io.kotest.matchers.shouldBe
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.lange.tennis.levelr.module
import kotlin.test.Test

/**
 * Snapshot tests that validate exact JSON payloads for successful requests.
 * These tests ensure the API contract remains stable by comparing
 * exact input/output JSON (minified for comparison).
 *
 * Focus: Exact payload matching for rating calculations
 * - NTRP tests: testNTRP_* (5 tests)
 * - UTR tests: testUTR_* (2 tests)
 *
 * For validation/error cases, see RankingCalculationApiTest.
 *
 * Uses Kotest assertions for improved readability.
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

    // ========================================
    // NTRP System Payload Tests
    // ========================================

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

            response.status shouldBe HttpStatusCode.OK

            val actualJson = response.bodyAsText()

            val expectedJson =
                """
                {
                  "players": {
                    "P1": {
                      "playerId": "P1",
                      "name": "Alice",
                      "rating": {
                        "value": "4.500000",
                        "system": "NTRP"
                      }
                    },
                    "P2": {
                      "playerId": "P2",
                      "name": "Bob",
                      "rating": {
                        "value": "4.000000",
                        "system": "NTRP"
                      }
                    }
                  },
                  "ratingChanges": {
                    "P1": {
                      "change": "0.000000",
                      "percentChange": "0.000000",
                      "previousRating": {
                        "value": "4.5",
                        "system": "NTRP"
                      },
                      "newRating": {
                        "value": "4.500000",
                        "system": "NTRP"
                      }
                    },
                    "P2": {
                      "change": "0.000000",
                      "percentChange": "0.000000",
                      "previousRating": {
                        "value": "4.0",
                        "system": "NTRP"
                      },
                      "newRating": {
                        "value": "4.000000",
                        "system": "NTRP"
                      }
                    }
                  }
                }
                """

            minifyJson(jsonString = actualJson) shouldBe minifyJson(jsonString = expectedJson.trimIndent())
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

            response.status shouldBe HttpStatusCode.OK

            val actualJson = response.bodyAsText()

            val expectedJson =
                """
                {
                  "players": {
                    "P1": {
                      "playerId": "P1",
                      "name": "Player One",
                      "rating": {
                        "value": "5.120000",
                        "system": "NTRP"
                      }
                    },
                    "P2": {
                      "playerId": "P2",
                      "name": "Player Two",
                      "rating": {
                        "value": "4.880000",
                        "system": "NTRP"
                      }
                    }
                  },
                  "ratingChanges": {
                    "P1": {
                      "change": "0.120000",
                      "percentChange": "2.400000",
                      "previousRating": {
                        "value": "5.0",
                        "system": "NTRP"
                      },
                      "newRating": {
                        "value": "5.120000",
                        "system": "NTRP"
                      }
                    },
                    "P2": {
                      "change": "-0.120000",
                      "percentChange": "-2.400000",
                      "previousRating": {
                        "value": "5.0",
                        "system": "NTRP"
                      },
                      "newRating": {
                        "value": "4.880000",
                        "system": "NTRP"
                      }
                    }
                  }
                }
                """

            minifyJson(jsonString = actualJson) shouldBe minifyJson(jsonString = expectedJson.trimIndent())
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

            response.status shouldBe HttpStatusCode.OK

            val actualJson = response.bodyAsText()

            val expectedJson =
                """
                {
                  "players": {
                    "WINNER": {
                      "playerId": "WINNER",
                      "name": "Dominant Player",
                      "rating": {
                        "value": "4.166667",
                        "system": "NTRP"
                      }
                    },
                    "LOSER": {
                      "playerId": "LOSER",
                      "name": "Weaker Player",
                      "rating": {
                        "value": "3.333333",
                        "system": "NTRP"
                      }
                    }
                  },
                  "ratingChanges": {
                    "WINNER": {
                      "change": "0.166667",
                      "percentChange": "4.166700",
                      "previousRating": {
                        "value": "4.0",
                        "system": "NTRP"
                      },
                      "newRating": {
                        "value": "4.166667",
                        "system": "NTRP"
                      }
                    },
                    "LOSER": {
                      "change": "-0.166667",
                      "percentChange": "-4.761900",
                      "previousRating": {
                        "value": "3.5",
                        "system": "NTRP"
                      },
                      "newRating": {
                        "value": "3.333333",
                        "system": "NTRP"
                      }
                    }
                  }
                }
                """

            minifyJson(jsonString = actualJson) shouldBe minifyJson(jsonString = expectedJson.trimIndent())
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

            response.status shouldBe HttpStatusCode.OK

            val actualJson = response.bodyAsText()

            // With large rating gap (2.0), expected margin (12 games) exceeds physical limit
            // Algorithm caps expected margin at actual winner's games (6), resulting in zero change
            // TODO: Review if large rating gap scenarios should behave differently
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
                        "value": "3.000000",
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
                      "change": "0.000000",
                      "percentChange": "0.000000",
                      "previousRating": {
                        "value": "3.0",
                        "system": "NTRP"
                      },
                      "newRating": {
                        "value": "3.000000",
                        "system": "NTRP"
                      }
                    }
                  }
                }
                """

            minifyJson(jsonString = actualJson) shouldBe minifyJson(jsonString = expectedJson.trimIndent())
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

            response.status shouldBe HttpStatusCode.OK

            val actualJson = response.bodyAsText()

            // With new algorithm: 6-0 with 1.0 rating diff has expected margin of 6
            // Actual margin = 6, expected = 6, so ratio = 1.0 (at threshold)
            // Result: zero change (at baseline)
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
                        "value": "6.000000",
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
                      "change": "0.000000",
                      "percentChange": "0.000000",
                      "previousRating": {
                        "value": "6.0",
                        "system": "NTRP"
                      },
                      "newRating": {
                        "value": "6.000000",
                        "system": "NTRP"
                      }
                    }
                  }
                }
                """

            minifyJson(jsonString = actualJson) shouldBe minifyJson(jsonString = expectedJson.trimIndent())
        }

    // ========================================
    // UTR System Payload Tests
    // ========================================

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

            response.status shouldBe HttpStatusCode.OK

            val actualJson = response.bodyAsText()

            val expectedJson =
                """
                {
                  "players": {
                    "P100": {
                      "playerId": "P100",
                      "name": "Pro Player",
                      "rating": {
                        "value": "12.394716",
                        "system": "UTR"
                      }
                    },
                    "P200": {
                      "playerId": "P200",
                      "name": "Amateur",
                      "rating": {
                        "value": "8.105284",
                        "system": "UTR"
                      }
                    }
                  },
                  "ratingChanges": {
                    "P100": {
                      "change": "-0.105284",
                      "percentChange": "-0.842300",
                      "previousRating": {
                        "value": "12.5",
                        "system": "UTR"
                      },
                      "newRating": {
                        "value": "12.394716",
                        "system": "UTR"
                      }
                    },
                    "P200": {
                      "change": "0.105284",
                      "percentChange": "1.316100",
                      "previousRating": {
                        "value": "8.0",
                        "system": "UTR"
                      },
                      "newRating": {
                        "value": "8.105284",
                        "system": "UTR"
                      }
                    }
                  }
                }
                """

            minifyJson(jsonString = actualJson) shouldBe minifyJson(jsonString = expectedJson.trimIndent())
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

            response.status shouldBe HttpStatusCode.OK

            val actualJson = response.bodyAsText()

            val expectedJson =
                """
                {
                  "players": {
                    "PA": {
                      "playerId": "PA",
                      "name": "Alex",
                      "rating": {
                        "value": "9.910050",
                        "system": "UTR"
                      }
                    },
                    "PB": {
                      "playerId": "PB",
                      "name": "Blake",
                      "rating": {
                        "value": "9.889950",
                        "system": "UTR"
                      }
                    }
                  },
                  "ratingChanges": {
                    "PA": {
                      "change": "-0.089950",
                      "percentChange": "-0.899500",
                      "previousRating": {
                        "value": "10.0",
                        "system": "UTR"
                      },
                      "newRating": {
                        "value": "9.910050",
                        "system": "UTR"
                      }
                    },
                    "PB": {
                      "change": "0.089950",
                      "percentChange": "0.917900",
                      "previousRating": {
                        "value": "9.8",
                        "system": "UTR"
                      },
                      "newRating": {
                        "value": "9.889950",
                        "system": "UTR"
                      }
                    }
                  }
                }
                """

            minifyJson(jsonString = actualJson) shouldBe minifyJson(jsonString = expectedJson.trimIndent())
        }
}
