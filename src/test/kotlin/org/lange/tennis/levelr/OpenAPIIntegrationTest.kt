package org.lange.tennis.levelr

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAPIIntegrationTest {
    @Test
    fun testOpenAPISpecEndpoint() =
        testApplication {
            application {
                module()
            }

            val response = client.get("/openapi")

            assertEquals(HttpStatusCode.OK, response.status)
            // OpenAPI endpoint returns HTML by default with Ktor's StaticHtml2Codegen
            assertTrue(response.contentType()?.match(ContentType.Text.Html) == true)

            val body = response.bodyAsText()
            // Verify it contains OpenAPI spec content
            assertTrue(body.contains("Tennis Levelr API"), "Response should contain API title")
            assertTrue(body.contains("/api/v1/calculate-ranking"), "Response should contain the API endpoint")
        }

    @Test
    fun testSwaggerUIEndpoint() =
        testApplication {
            application {
                module()
            }

            val response = client.get("/swagger")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.contentType()?.match(ContentType.Text.Html) == true)

            val body = response.bodyAsText()
            // Verify it's actually Swagger UI
            assertTrue(body.contains("swagger-ui") || body.contains("Swagger UI"), "Response should contain Swagger UI")
        }
}
