// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test

class OpenAPIIntegrationTest {
    @Test
    fun testOpenAPISpecEndpoint() =
        testApplication {
            application {
                module(initDatabase = false)
            }

            val response = client.get(urlString = "/openapi.yaml")

            response.status shouldBe HttpStatusCode.OK
            // OpenAPI YAML file should be served as plain text
            response.contentType()?.match(ContentType.Text.Plain) shouldBe true

            val body = response.bodyAsText()
            // Verify it contains OpenAPI spec content
            body shouldContain "openapi: 3.0.0"
            body shouldContain "Skopeo API"
            body shouldContain "/api/v1/calculate-ranking"
            body shouldContain "/api/v1/theme"
            // The paged standings serving layer (#220): the page endpoint, the jump-to-me endpoint,
            // and the response schemas are all documented.
            body shouldContain "/api/v1/standings/me"
            body shouldContain "StandingsPageResponse"
            body shouldContain "StandingsLocateResponse"
            body shouldContain "/ranking-points"
        }

    @Test
    fun testSwaggerUIEndpoint() =
        testApplication {
            application {
                module(initDatabase = false)
            }

            val response = client.get(urlString = "/swagger")

            response.status shouldBe HttpStatusCode.OK
            response.contentType()?.match(ContentType.Text.Html) shouldBe true

            val body = response.bodyAsText()
            // Verify it's actually Swagger UI
            (body.contains(other = "swagger-ui") || body.contains(other = "Swagger UI")) shouldBe true
        }
}
