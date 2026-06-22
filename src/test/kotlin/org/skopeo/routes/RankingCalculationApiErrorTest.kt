// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.skopeo.module
import kotlin.test.Test

/**
 * Integration tests for the Ranking Calculation API.
 *
 * These tests focus on API-level integration and HTTP responses.
 * Detailed validation logic is covered in unit tests.
 * Detailed payload testing is in RankingCalculationPayloadTest.
 */
class RankingCalculationApiErrorTest {
    @Test
    fun testSuccessfulNTRPRequest() =
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
                      "name": "Player 1",
                      "players": [{"playerId": "P1", "name": "Player 1", "rating": {"value": "4.5", "system": "NTRP"}}],
                      "teamType": "SINGLES"
                    },
                    "T2": {
                      "teamId": "T2",
                      "name": "Player 2",
                      "players": [{"playerId": "P2", "name": "Player 2", "rating": {"value": "4.0", "system": "NTRP"}}],
                      "teamType": "SINGLES"
                    }
                  },
                  "matchScore": {
                    "sets": [{"games": {"T1": 6, "T2": 4}, "winnerTeamId": "T1"}]
                  }
                }
                """

            val response =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody(requestJson.trimIndent())
                }

            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "ratingChanges"
            body shouldContain "players"
            body shouldContain "teams"
        }

    @Test
    fun testSuccessfulUTRRequest() =
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
                      "name": "Player 1",
                      "players": [{"playerId": "P1", "name": "Player 1", "rating": {"value": "12.5", "system": "UTR"}}],
                      "teamType": "SINGLES"
                    },
                    "T2": {
                      "teamId": "T2",
                      "name": "Player 2",
                      "players": [{"playerId": "P2", "name": "Player 2", "rating": {"value": "10.0", "system": "UTR"}}],
                      "teamType": "SINGLES"
                    }
                  },
                  "matchScore": {
                    "sets": [{"games": {"T1": 6, "T2": 2}, "winnerTeamId": "T1"}]
                  }
                }
                """

            val response =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody(requestJson.trimIndent())
                }

            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "ratingChanges"
            body shouldContain "players"
            body shouldContain "teams"
        }

    @Test
    fun testSuccessfulRequestWithExplicitWinnerAndLoser() =
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
                      "name": "Player 1",
                      "players": [{"playerId": "P1", "name": "Player 1", "rating": {"value": "4.5", "system": "NTRP"}}],
                      "teamType": "SINGLES"
                    },
                    "T2": {
                      "teamId": "T2",
                      "name": "Player 2",
                      "players": [{"playerId": "P2", "name": "Player 2", "rating": {"value": "4.0", "system": "NTRP"}}],
                      "teamType": "SINGLES"
                    }
                  },
                  "matchScore": {
                    "sets": [{"games": {"T1": 6, "T2": 4}, "winnerTeamId": "T1"}],
                    "winnerTeamId": "T1",
                    "loserTeamId": "T2"
                  }
                }
                """

            val response =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody(requestJson.trimIndent())
                }

            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "ratingChanges"
        }

    @Test
    fun testErrorResponse_LoserSameAsWinner() =
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
                      "name": "Player 1",
                      "players": [{"playerId": "P1", "name": "Player 1", "rating": {"value": "4.5", "system": "NTRP"}}],
                      "teamType": "SINGLES"
                    },
                    "T2": {
                      "teamId": "T2",
                      "name": "Player 2",
                      "players": [{"playerId": "P2", "name": "Player 2", "rating": {"value": "4.0", "system": "NTRP"}}],
                      "teamType": "SINGLES"
                    }
                  },
                  "matchScore": {
                    "sets": [{"games": {"T1": 6, "T2": 4}, "winnerTeamId": "T1"}],
                    "winnerTeamId": "T1",
                    "loserTeamId": "T1"
                  }
                }
                """

            val response =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody(requestJson.trimIndent())
                }

            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "must differ from the winner"
        }

    @Test
    fun testErrorResponse_InvalidJson() =
        testApplication {
            application {
                module(initDatabase = false)
            }

            val requestJson = """not valid json at all"""

            val response =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody(requestJson)
                }

            // Should return 400 or 500 - either is acceptable for malformed JSON
            (response.status == HttpStatusCode.BadRequest || response.status == HttpStatusCode.InternalServerError) shouldBe true
            val body = response.bodyAsText()
            body shouldContain "error"
        }

    @Test
    fun testErrorResponse_EmptyBody() =
        testApplication {
            application {
                module(initDatabase = false)
            }

            val response =
                client.post("/api/v1/calculate-ranking") {
                    contentType(ContentType.Application.Json)
                    setBody("")
                }

            // Should return 400 or 500 - either is acceptable for empty body
            (response.status == HttpStatusCode.BadRequest || response.status == HttpStatusCode.InternalServerError) shouldBe true
            val body = response.bodyAsText()
            body shouldContain "error"
        }
}
