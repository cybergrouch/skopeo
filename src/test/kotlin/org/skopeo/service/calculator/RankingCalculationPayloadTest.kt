// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.calculator

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
import org.junit.jupiter.api.Test
import org.skopeo.module

/**
 * Snapshot tests that validate exact JSON payloads for successful requests.
 * These tests ensure the API contract remains stable by comparing
 * exact input/output JSON (minified for comparison).
 *
 * Focus: Exact payload matching for rating calculations
 * - NTRP tests: testNTRP_* (5 tests)
 * - UTR tests: testUTR_* (2 tests)
 *
 * For validation/error cases, see RankingCalculationApiErrorTest.
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
                module(initDatabase = false)
            }

            val requestJson =
                """
                {
                  "teams": {
                    "T1": {
                      "teamId": "T1",
                      "name": "Alice",
                      "players": [{
                        "playerId": "P1",
                        "name": "Alice",
                        "rating": {
                          "value": "4.5",
                          "system": "NTRP"
                        }
                      }],
                      "teamType": "SINGLES"
                    },
                    "T2": {
                      "teamId": "T2",
                      "name": "Bob",
                      "players": [{
                        "playerId": "P2",
                        "name": "Bob",
                        "rating": {
                          "value": "4.0",
                          "system": "NTRP"
                        }
                      }],
                      "teamType": "SINGLES"
                    }
                  },
                  "matchScore": {
                    "sets": [
                      {
                        "games": {
                          "T1": 6,
                          "T2": 3
                        },
                        "winnerTeamId": "T1"
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
                  "ratingChanges": {
                    "P1": {
                      "change": "0.000000",
                      "previousRating": {
                        "value": "4.5",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "4.5",
                          "minRating": "4.5",
                          "maxRating": "5.0",
                          "system": "NTRP"
                        }
                      },
                      "newRating": {
                        "value": "4.500000",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "4.5",
                          "minRating": "4.5",
                          "maxRating": "5.0",
                          "system": "NTRP"
                        }
                      },
                      "percentChange": "0.000000",
                      "levelChanged": false
                    },
                    "P2": {
                      "change": "0.000000",
                      "previousRating": {
                        "value": "4.0",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "4.0",
                          "minRating": "4.0",
                          "maxRating": "4.5",
                          "system": "NTRP"
                        }
                      },
                      "newRating": {
                        "value": "4.000000",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "4.0",
                          "minRating": "4.0",
                          "maxRating": "4.5",
                          "system": "NTRP"
                        }
                      },
                      "percentChange": "0.000000",
                      "levelChanged": false
                    }
                  },
                  "players": {
                    "P1": {
                      "playerId": "P1",
                      "name": "Alice",
                      "rating": {
                        "value": "4.500000",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "4.5",
                          "minRating": "4.5",
                          "maxRating": "5.0",
                          "system": "NTRP"
                        }
                      }
                    },
                    "P2": {
                      "playerId": "P2",
                      "name": "Bob",
                      "rating": {
                        "value": "4.000000",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "4.0",
                          "minRating": "4.0",
                          "maxRating": "4.5",
                          "system": "NTRP"
                        }
                      }
                    }
                  },
                  "teams": {
                    "T1": {
                      "teamId": "T1",
                      "name": "Alice",
                      "players": [{
                        "playerId": "P1",
                        "name": "Alice",
                        "rating": {
                          "value": "4.500000",
                          "system": "NTRP",
                          "publishedLevel": {
                            "value": "4.5",
                            "minRating": "4.5",
                            "maxRating": "5.0",
                            "system": "NTRP"
                          }
                        }
                      }],
                      "teamType": "SINGLES"
                    },
                    "T2": {
                      "teamId": "T2",
                      "name": "Bob",
                      "players": [{
                        "playerId": "P2",
                        "name": "Bob",
                        "rating": {
                          "value": "4.000000",
                          "system": "NTRP",
                          "publishedLevel": {
                            "value": "4.0",
                            "minRating": "4.0",
                            "maxRating": "4.5",
                            "system": "NTRP"
                          }
                        }
                      }],
                      "teamType": "SINGLES"
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
                module(initDatabase = false)
            }

            val requestJson =
                """
                {
                  "teams": {
                    "T1": {
                      "teamId": "T1",
                      "name": "Player One",
                      "players": [{
                        "playerId": "P1",
                        "name": "Player One",
                        "rating": {
                          "value": "5.0",
                          "system": "NTRP"
                        }
                      }],
                      "teamType": "SINGLES"
                    },
                    "T2": {
                      "teamId": "T2",
                      "name": "Player Two",
                      "players": [{
                        "playerId": "P2",
                        "name": "Player Two",
                        "rating": {
                          "value": "5.0",
                          "system": "NTRP"
                        }
                      }],
                      "teamType": "SINGLES"
                    }
                  },
                  "matchScore": {
                    "sets": [
                      {
                        "games": {
                          "T1": 6,
                          "T2": 4
                        },
                        "winnerTeamId": "T1"
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
                  "ratingChanges": {
                    "P1": {
                      "change": "0.032000",
                      "previousRating": {
                        "value": "5.0",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "5.0",
                          "minRating": "5.0",
                          "maxRating": "5.5",
                          "system": "NTRP"
                        }
                      },
                      "newRating": {
                        "value": "5.032000",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "5.0",
                          "minRating": "5.0",
                          "maxRating": "5.5",
                          "system": "NTRP"
                        }
                      },
                      "percentChange": "0.640000",
                      "levelChanged": false
                    },
                    "P2": {
                      "change": "-0.032000",
                      "previousRating": {
                        "value": "5.0",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "5.0",
                          "minRating": "5.0",
                          "maxRating": "5.5",
                          "system": "NTRP"
                        }
                      },
                      "newRating": {
                        "value": "4.968000",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "4.5",
                          "minRating": "4.5",
                          "maxRating": "5.0",
                          "system": "NTRP"
                        }
                      },
                      "percentChange": "-0.640000",
                      "levelChanged": true
                    }
                  },
                  "players": {
                    "P1": {
                      "playerId": "P1",
                      "name": "Player One",
                      "rating": {
                        "value": "5.032000",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "5.0",
                          "minRating": "5.0",
                          "maxRating": "5.5",
                          "system": "NTRP"
                        }
                      }
                    },
                    "P2": {
                      "playerId": "P2",
                      "name": "Player Two",
                      "rating": {
                        "value": "4.968000",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "4.5",
                          "minRating": "4.5",
                          "maxRating": "5.0",
                          "system": "NTRP"
                        }
                      }
                    }
                  },
                  "teams": {
                    "T1": {
                      "teamId": "T1",
                      "name": "Player One",
                      "players": [{
                        "playerId": "P1",
                        "name": "Player One",
                        "rating": {
                          "value": "5.032000",
                          "system": "NTRP",
                          "publishedLevel": {
                            "value": "5.0",
                            "minRating": "5.0",
                            "maxRating": "5.5",
                            "system": "NTRP"
                          }
                        }
                      }],
                      "teamType": "SINGLES"
                    },
                    "T2": {
                      "teamId": "T2",
                      "name": "Player Two",
                      "players": [{
                        "playerId": "P2",
                        "name": "Player Two",
                        "rating": {
                          "value": "4.968000",
                          "system": "NTRP",
                          "publishedLevel": {
                            "value": "4.5",
                            "minRating": "4.5",
                            "maxRating": "5.0",
                            "system": "NTRP"
                          }
                        }
                      }],
                      "teamType": "SINGLES"
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
                module(initDatabase = false)
            }

            val requestJson =
                """
                {
                  "teams": {
                    "T1": {
                      "teamId": "T1",
                      "name": "Dominant Player",
                      "players": [{
                        "playerId": "WINNER",
                        "name": "Dominant Player",
                        "rating": {
                          "value": "4.0",
                          "system": "NTRP"
                        }
                      }],
                      "teamType": "SINGLES"
                    },
                    "T2": {
                      "teamId": "T2",
                      "name": "Weaker Player",
                      "players": [{
                        "playerId": "LOSER",
                        "name": "Weaker Player",
                        "rating": {
                          "value": "3.5",
                          "system": "NTRP"
                        }
                      }],
                      "teamType": "SINGLES"
                    }
                  },
                  "matchScore": {
                    "sets": [
                      {
                        "games": {
                          "T1": 6,
                          "T2": 0
                        },
                        "winnerTeamId": "T1"
                      },
                      {
                        "games": {
                          "T1": 6,
                          "T2": 1
                        },
                        "winnerTeamId": "T1"
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
                  "ratingChanges": {
                    "WINNER": {
                      "change": "0.000000",
                      "previousRating": {
                        "value": "4.0",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "4.0",
                          "minRating": "4.0",
                          "maxRating": "4.5",
                          "system": "NTRP"
                        }
                      },
                      "newRating": {
                        "value": "4.000000",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "4.0",
                          "minRating": "4.0",
                          "maxRating": "4.5",
                          "system": "NTRP"
                        }
                      },
                      "percentChange": "0.000000",
                      "levelChanged": false
                    },
                    "LOSER": {
                      "change": "0.000000",
                      "previousRating": {
                        "value": "3.5",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "3.5",
                          "minRating": "3.5",
                          "maxRating": "4.0",
                          "system": "NTRP"
                        }
                      },
                      "newRating": {
                        "value": "3.500000",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "3.5",
                          "minRating": "3.5",
                          "maxRating": "4.0",
                          "system": "NTRP"
                        }
                      },
                      "percentChange": "0.000000",
                      "levelChanged": false
                    }
                  },
                  "players": {
                    "WINNER": {
                      "playerId": "WINNER",
                      "name": "Dominant Player",
                      "rating": {
                        "value": "4.000000",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "4.0",
                          "minRating": "4.0",
                          "maxRating": "4.5",
                          "system": "NTRP"
                        }
                      }
                    },
                    "LOSER": {
                      "playerId": "LOSER",
                      "name": "Weaker Player",
                      "rating": {
                        "value": "3.500000",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "3.5",
                          "minRating": "3.5",
                          "maxRating": "4.0",
                          "system": "NTRP"
                        }
                      }
                    }
                  },
                  "teams": {
                    "T1": {
                      "teamId": "T1",
                      "name": "Dominant Player",
                      "players": [{
                        "playerId": "WINNER",
                        "name": "Dominant Player",
                        "rating": {
                          "value": "4.000000",
                          "system": "NTRP",
                          "publishedLevel": {
                            "value": "4.0",
                            "minRating": "4.0",
                            "maxRating": "4.5",
                            "system": "NTRP"
                          }
                        }
                      }],
                      "teamType": "SINGLES"
                    },
                    "T2": {
                      "teamId": "T2",
                      "name": "Weaker Player",
                      "players": [{
                        "playerId": "LOSER",
                        "name": "Weaker Player",
                        "rating": {
                          "value": "3.500000",
                          "system": "NTRP",
                          "publishedLevel": {
                            "value": "3.5",
                            "minRating": "3.5",
                            "maxRating": "4.0",
                            "system": "NTRP"
                          }
                        }
                      }],
                      "teamType": "SINGLES"
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
                module(initDatabase = false)
            }

            val requestJson =
                """
                {
                  "teams": {
                    "T1": {
                      "teamId": "T1",
                      "name": "Beginner",
                      "players": [{
                        "playerId": "P1",
                        "name": "Beginner",
                        "rating": {
                          "value": "1.0",
                          "system": "NTRP"
                        }
                      }],
                      "teamType": "SINGLES"
                    },
                    "T2": {
                      "teamId": "T2",
                      "name": "Intermediate",
                      "players": [{
                        "playerId": "P2",
                        "name": "Intermediate",
                        "rating": {
                          "value": "3.0",
                          "system": "NTRP"
                        }
                      }],
                      "teamType": "SINGLES"
                    }
                  },
                  "matchScore": {
                    "sets": [
                      {
                        "games": {
                          "T1": 0,
                          "T2": 6
                        },
                        "winnerTeamId": "T2"
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
                  "ratingChanges": {
                    "P1": {
                      "change": "0.000000",
                      "previousRating": {
                        "value": "1.0",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "1.0",
                          "minRating": "1.0",
                          "maxRating": "1.5",
                          "system": "NTRP"
                        }
                      },
                      "newRating": {
                        "value": "1.000000",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "1.0",
                          "minRating": "1.0",
                          "maxRating": "1.5",
                          "system": "NTRP"
                        }
                      },
                      "percentChange": "0.000000",
                      "levelChanged": false
                    },
                    "P2": {
                      "change": "0.000000",
                      "previousRating": {
                        "value": "3.0",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "3.0",
                          "minRating": "3.0",
                          "maxRating": "3.5",
                          "system": "NTRP"
                        }
                      },
                      "newRating": {
                        "value": "3.000000",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "3.0",
                          "minRating": "3.0",
                          "maxRating": "3.5",
                          "system": "NTRP"
                        }
                      },
                      "percentChange": "0.000000",
                      "levelChanged": false
                    }
                  },
                  "players": {
                    "P1": {
                      "playerId": "P1",
                      "name": "Beginner",
                      "rating": {
                        "value": "1.000000",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "1.0",
                          "minRating": "1.0",
                          "maxRating": "1.5",
                          "system": "NTRP"
                        }
                      }
                    },
                    "P2": {
                      "playerId": "P2",
                      "name": "Intermediate",
                      "rating": {
                        "value": "3.000000",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "3.0",
                          "minRating": "3.0",
                          "maxRating": "3.5",
                          "system": "NTRP"
                        }
                      }
                    }
                  },
                  "teams": {
                    "T1": {
                      "teamId": "T1",
                      "name": "Beginner",
                      "players": [{
                        "playerId": "P1",
                        "name": "Beginner",
                        "rating": {
                          "value": "1.000000",
                          "system": "NTRP",
                          "publishedLevel": {
                            "value": "1.0",
                            "minRating": "1.0",
                            "maxRating": "1.5",
                            "system": "NTRP"
                          }
                        }
                      }],
                      "teamType": "SINGLES"
                    },
                    "T2": {
                      "teamId": "T2",
                      "name": "Intermediate",
                      "players": [{
                        "playerId": "P2",
                        "name": "Intermediate",
                        "rating": {
                          "value": "3.000000",
                          "system": "NTRP",
                          "publishedLevel": {
                            "value": "3.0",
                            "minRating": "3.0",
                            "maxRating": "3.5",
                            "system": "NTRP"
                          }
                        }
                      }],
                      "teamType": "SINGLES"
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
                module(initDatabase = false)
            }

            val requestJson =
                """
                {
                  "teams": {
                    "T1": {
                      "teamId": "T1",
                      "name": "Expert",
                      "players": [{
                        "playerId": "P1",
                        "name": "Expert",
                        "rating": {
                          "value": "7.0",
                          "system": "NTRP"
                        }
                      }],
                      "teamType": "SINGLES"
                    },
                    "T2": {
                      "teamId": "T2",
                      "name": "Advanced",
                      "players": [{
                        "playerId": "P2",
                        "name": "Advanced",
                        "rating": {
                          "value": "6.0",
                          "system": "NTRP"
                        }
                      }],
                      "teamType": "SINGLES"
                    }
                  },
                  "matchScore": {
                    "sets": [
                      {
                        "games": {
                          "T1": 6,
                          "T2": 0
                        },
                        "winnerTeamId": "T1"
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
                  "ratingChanges": {
                    "P1": {
                      "change": "0.000000",
                      "previousRating": {
                        "value": "7.0",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "7.0",
                          "minRating": "7.0",
                          "maxRating": null,
                          "system": "NTRP"
                        }
                      },
                      "newRating": {
                        "value": "7.000000",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "7.0",
                          "minRating": "7.0",
                          "maxRating": null,
                          "system": "NTRP"
                        }
                      },
                      "percentChange": "0.000000",
                      "levelChanged": false
                    },
                    "P2": {
                      "change": "0.000000",
                      "previousRating": {
                        "value": "6.0",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "6.0",
                          "minRating": "6.0",
                          "maxRating": "6.5",
                          "system": "NTRP"
                        }
                      },
                      "newRating": {
                        "value": "6.000000",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "6.0",
                          "minRating": "6.0",
                          "maxRating": "6.5",
                          "system": "NTRP"
                        }
                      },
                      "percentChange": "0.000000",
                      "levelChanged": false
                    }
                  },
                  "players": {
                    "P1": {
                      "playerId": "P1",
                      "name": "Expert",
                      "rating": {
                        "value": "7.000000",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "7.0",
                          "minRating": "7.0",
                          "maxRating": null,
                          "system": "NTRP"
                        }
                      }
                    },
                    "P2": {
                      "playerId": "P2",
                      "name": "Advanced",
                      "rating": {
                        "value": "6.000000",
                        "system": "NTRP",
                        "publishedLevel": {
                          "value": "6.0",
                          "minRating": "6.0",
                          "maxRating": "6.5",
                          "system": "NTRP"
                        }
                      }
                    }
                  },
                  "teams": {
                    "T1": {
                      "teamId": "T1",
                      "name": "Expert",
                      "players": [{
                        "playerId": "P1",
                        "name": "Expert",
                        "rating": {
                          "value": "7.000000",
                          "system": "NTRP",
                          "publishedLevel": {
                            "value": "7.0",
                            "minRating": "7.0",
                            "maxRating": null,
                            "system": "NTRP"
                          }
                        }
                      }],
                      "teamType": "SINGLES"
                    },
                    "T2": {
                      "teamId": "T2",
                      "name": "Advanced",
                      "players": [{
                        "playerId": "P2",
                        "name": "Advanced",
                        "rating": {
                          "value": "6.000000",
                          "system": "NTRP",
                          "publishedLevel": {
                            "value": "6.0",
                            "minRating": "6.0",
                            "maxRating": "6.5",
                            "system": "NTRP"
                          }
                        }
                      }],
                      "teamType": "SINGLES"
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
                module(initDatabase = false)
            }

            val requestJson =
                """
                {
                  "teams": {
                    "T1": {
                      "teamId": "T1",
                      "name": "Pro Player",
                      "players": [{
                        "playerId": "P100",
                        "name": "Pro Player",
                        "rating": {
                          "value": "12.5",
                          "system": "UTR"
                        }
                      }],
                      "teamType": "SINGLES"
                    },
                    "T2": {
                      "teamId": "T2",
                      "name": "Amateur",
                      "players": [{
                        "playerId": "P200",
                        "name": "Amateur",
                        "rating": {
                          "value": "8.0",
                          "system": "UTR"
                        }
                      }],
                      "teamType": "SINGLES"
                    }
                  },
                  "matchScore": {
                    "sets": [
                      {
                        "games": {
                          "T1": 6,
                          "T2": 2
                        },
                        "winnerTeamId": "T1"
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
                  "ratingChanges": {
                    "P100": {
                      "change": "0.000000",
                      "previousRating": {
                        "value": "12.5",
                        "system": "UTR",
                        "publishedLevel": {
                          "value": "12.0",
                          "minRating": "12.0",
                          "maxRating": "13.0",
                          "system": "UTR"
                        }
                      },
                      "newRating": {
                        "value": "12.500000",
                        "system": "UTR",
                        "publishedLevel": {
                          "value": "12.0",
                          "minRating": "12.0",
                          "maxRating": "13.0",
                          "system": "UTR"
                        }
                      },
                      "percentChange": "0.000000",
                      "levelChanged": false
                    },
                    "P200": {
                      "change": "0.000000",
                      "previousRating": {
                        "value": "8.0",
                        "system": "UTR",
                        "publishedLevel": {
                          "value": "8.0",
                          "minRating": "8.0",
                          "maxRating": "9.0",
                          "system": "UTR"
                        }
                      },
                      "newRating": {
                        "value": "8.000000",
                        "system": "UTR",
                        "publishedLevel": {
                          "value": "8.0",
                          "minRating": "8.0",
                          "maxRating": "9.0",
                          "system": "UTR"
                        }
                      },
                      "percentChange": "0.000000",
                      "levelChanged": false
                    }
                  },
                  "players": {
                    "P100": {
                      "playerId": "P100",
                      "name": "Pro Player",
                      "rating": {
                        "value": "12.500000",
                        "system": "UTR",
                        "publishedLevel": {
                          "value": "12.0",
                          "minRating": "12.0",
                          "maxRating": "13.0",
                          "system": "UTR"
                        }
                      }
                    },
                    "P200": {
                      "playerId": "P200",
                      "name": "Amateur",
                      "rating": {
                        "value": "8.000000",
                        "system": "UTR",
                        "publishedLevel": {
                          "value": "8.0",
                          "minRating": "8.0",
                          "maxRating": "9.0",
                          "system": "UTR"
                        }
                      }
                    }
                  },
                  "teams": {
                    "T1": {
                      "teamId": "T1",
                      "name": "Pro Player",
                      "players": [{
                        "playerId": "P100",
                        "name": "Pro Player",
                        "rating": {
                          "value": "12.500000",
                          "system": "UTR",
                          "publishedLevel": {
                            "value": "12.0",
                            "minRating": "12.0",
                            "maxRating": "13.0",
                            "system": "UTR"
                          }
                        }
                      }],
                      "teamType": "SINGLES"
                    },
                    "T2": {
                      "teamId": "T2",
                      "name": "Amateur",
                      "players": [{
                        "playerId": "P200",
                        "name": "Amateur",
                        "rating": {
                          "value": "8.000000",
                          "system": "UTR",
                          "publishedLevel": {
                            "value": "8.0",
                            "minRating": "8.0",
                            "maxRating": "9.0",
                            "system": "UTR"
                          }
                        }
                      }],
                      "teamType": "SINGLES"
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
                module(initDatabase = false)
            }

            val requestJson =
                """
                {
                  "teams": {
                    "T1": {
                      "teamId": "T1",
                      "name": "Alex",
                      "players": [{
                        "playerId": "PA",
                        "name": "Alex",
                        "rating": {
                          "value": "10.0",
                          "system": "UTR"
                        }
                      }],
                      "teamType": "SINGLES"
                    },
                    "T2": {
                      "teamId": "T2",
                      "name": "Blake",
                      "players": [{
                        "playerId": "PB",
                        "name": "Blake",
                        "rating": {
                          "value": "9.8",
                          "system": "UTR"
                        }
                      }],
                      "teamType": "SINGLES"
                    }
                  },
                  "matchScore": {
                    "sets": [
                      {
                        "games": {
                          "T1": 7,
                          "T2": 6
                        },
                        "tiebreak": {
                          "points": {
                            "T1": 7,
                            "T2": 5
                          },
                          "winnerTeamId": "T1"
                        },
                        "winnerTeamId": "T1"
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
                  "ratingChanges": {
                    "PA": {
                      "change": "0.025826",
                      "previousRating": {
                        "value": "10.0",
                        "system": "UTR",
                        "publishedLevel": {
                          "value": "10.0",
                          "minRating": "10.0",
                          "maxRating": "11.0",
                          "system": "UTR"
                        }
                      },
                      "newRating": {
                        "value": "10.025826",
                        "system": "UTR",
                        "publishedLevel": {
                          "value": "10.0",
                          "minRating": "10.0",
                          "maxRating": "11.0",
                          "system": "UTR"
                        }
                      },
                      "percentChange": "0.258300",
                      "levelChanged": false
                    },
                    "PB": {
                      "change": "-0.025826",
                      "previousRating": {
                        "value": "9.8",
                        "system": "UTR",
                        "publishedLevel": {
                          "value": "9.0",
                          "minRating": "9.0",
                          "maxRating": "10.0",
                          "system": "UTR"
                        }
                      },
                      "newRating": {
                        "value": "9.774174",
                        "system": "UTR",
                        "publishedLevel": {
                          "value": "9.0",
                          "minRating": "9.0",
                          "maxRating": "10.0",
                          "system": "UTR"
                        }
                      },
                      "percentChange": "-0.263500",
                      "levelChanged": false
                    }
                  },
                  "players": {
                    "PA": {
                      "playerId": "PA",
                      "name": "Alex",
                      "rating": {
                        "value": "10.025826",
                        "system": "UTR",
                        "publishedLevel": {
                          "value": "10.0",
                          "minRating": "10.0",
                          "maxRating": "11.0",
                          "system": "UTR"
                        }
                      }
                    },
                    "PB": {
                      "playerId": "PB",
                      "name": "Blake",
                      "rating": {
                        "value": "9.774174",
                        "system": "UTR",
                        "publishedLevel": {
                          "value": "9.0",
                          "minRating": "9.0",
                          "maxRating": "10.0",
                          "system": "UTR"
                        }
                      }
                    }
                  },
                  "teams": {
                    "T1": {
                      "teamId": "T1",
                      "name": "Alex",
                      "players": [{
                        "playerId": "PA",
                        "name": "Alex",
                        "rating": {
                          "value": "10.025826",
                          "system": "UTR",
                          "publishedLevel": {
                            "value": "10.0",
                            "minRating": "10.0",
                            "maxRating": "11.0",
                            "system": "UTR"
                          }
                        }
                      }],
                      "teamType": "SINGLES"
                    },
                    "T2": {
                      "teamId": "T2",
                      "name": "Blake",
                      "players": [{
                        "playerId": "PB",
                        "name": "Blake",
                        "rating": {
                          "value": "9.774174",
                          "system": "UTR",
                          "publishedLevel": {
                            "value": "9.0",
                            "minRating": "9.0",
                            "maxRating": "10.0",
                            "system": "UTR"
                          }
                        }
                      }],
                      "teamType": "SINGLES"
                    }
                  }
                }
                """

            minifyJson(jsonString = actualJson) shouldBe minifyJson(jsonString = expectedJson.trimIndent())
        }
}
