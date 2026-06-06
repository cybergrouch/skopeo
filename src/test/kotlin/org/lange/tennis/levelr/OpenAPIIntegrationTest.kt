package org.lange.tennis.levelr

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
                module()
            }

            val response = client.get("/openapi.yaml")

            response.status shouldBe HttpStatusCode.OK
            // OpenAPI YAML file should be served as plain text
            response.contentType()?.match(ContentType.Text.Plain) shouldBe true

            val body = response.bodyAsText()
            // Verify it contains OpenAPI spec content
            body shouldContain "openapi: 3.0.0"
            body shouldContain "Tennis Levelr API"
            body shouldContain "/api/v1/calculate-ranking"
        }

    @Test
    fun testSwaggerUIEndpoint() =
        testApplication {
            application {
                module()
            }

            val response = client.get("/swagger")

            response.status shouldBe HttpStatusCode.OK
            response.contentType()?.match(ContentType.Text.Html) shouldBe true

            val body = response.bodyAsText()
            // Verify it's actually Swagger UI
            (body.contains("swagger-ui") || body.contains("Swagger UI")) shouldBe true
        }
}
