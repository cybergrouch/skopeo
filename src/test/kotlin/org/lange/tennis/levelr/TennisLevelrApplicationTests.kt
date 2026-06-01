package org.lange.tennis.levelr

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TennisLevelrApplicationTests {
    @Test
    fun testRoot() =
        testApplication {
            application {
                module()
            }

            val response = client.get("/")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("Tennis Levelr API", response.bodyAsText())
        }

    @Test
    fun testHealth() =
        testApplication {
            application {
                module()
            }

            val response = client.get("/health")
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(body.contains("\"status\":\"UP\""), "Health response should contain status UP")
            assertTrue(body.contains("\"service\":\"Tennis Levelr API\""), "Health response should contain service name")
            assertTrue(body.contains("\"version\":\"0.0.1-SNAPSHOT\""), "Health response should contain version")
        }

    @Test
    fun testMetrics() =
        testApplication {
            application {
                module()
            }

            val response = client.get("/metrics")
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(body.isNotEmpty(), "Metrics response should not be empty")
            assertTrue(
                body.contains("jvm") || body.contains("http"),
                "Metrics should contain JVM or HTTP metrics",
            )
        }
}
