package org.lange.tennis.levelr

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

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
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("OK", response.bodyAsText())
        }
}
