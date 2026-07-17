// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.yaml.snakeyaml.Yaml
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
            // The points-based recompute trigger (#146 phase 2).
            body shouldContain "/api/v1/standings/calculations"
            body shouldContain "StandingsCalculationResponse"
            body shouldContain "/ranking-points"
            // Event types + finalize state (#403 Phase A): the finalize path is documented.
            body shouldContain "/api/v1/events/{id}/finalize"
            // Points budget foundation (#403 Phase B): the policy + budget paths and schemas.
            body shouldContain "/api/v1/points/policies"
            body shouldContain "/api/v1/points/budgets"
            body shouldContain "/api/v1/clubs/{clubId}/point-budgets/{eventType}"
            body shouldContain "PointsPolicyResponse"
            body shouldContain "ClubBudgetResponse"
        }

    @Test
    fun testOpenAPISpecParsesAsValidYaml() {
        // Substring checks alone let a malformed documentation.yaml through the backend gate and
        // only break later in the web orval step (this happened in #400: a description with an
        // unquoted ": " mid-string made the YAML invalid). Parse it here so the backend gate fails
        // on malformed specs. Read the classpath resource directly — no server needed.
        val specStream =
            javaClass.classLoader.getResourceAsStream("openapi/documentation.yaml")
        val specText =
            requireNotNull(value = specStream).bufferedReader().use { reader -> reader.readText() }

        var parsed: Map<String, Any>? = null
        shouldNotThrowAny {
            @Suppress("UNCHECKED_CAST")
            parsed = Yaml().load<Any>(specText) as Map<String, Any>
        }

        // A structurally-broken spec (valid YAML but missing the OpenAPI skeleton) should also fail.
        val document = parsed.shouldNotBeNull()
        document.shouldContainKey(key = "openapi")
        document.shouldContainKey(key = "paths")

        @Suppress("UNCHECKED_CAST")
        val components = document["components"] as? Map<String, Any>
        components.shouldNotBeNull()
        components.shouldContainKey(key = "schemas")
    }

    @Test
    fun testServedOpenAPISpecParsesAsValidYaml() =
        testApplication {
            application {
                module(initDatabase = false)
            }

            val body = client.get(urlString = "/openapi.yaml").bodyAsText()

            // The body actually served over HTTP must also be parseable, not just the resource file.
            shouldNotThrowAny {
                Yaml().load<Any>(body)
            }
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
