package org.lange.tennis.levelr

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test

class TennisLevelrApplicationTests {
    @Test
    fun testRoot() =
        testApplication {
            application {
                module()
            }

            val response = client.get("/")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "Tennis Levelr API"
        }

    @Test
    fun testHealth() =
        testApplication {
            application {
                module()
            }

            val response = client.get("/health")
            val body = response.bodyAsText()

            response.status shouldBe HttpStatusCode.OK
            body shouldContain "\"status\":\"UP\""
            body shouldContain "\"service\":\"Tennis Levelr API\""
            body shouldContain "\"version\":\"0.0.1-SNAPSHOT\""
        }

    @Test
    fun testMetrics() =
        testApplication {
            application {
                module()
            }

            val response = client.get("/metrics")
            val body = response.bodyAsText()

            response.status shouldBe HttpStatusCode.OK
            body shouldNotBe ""
            (body.contains("jvm") || body.contains("http")) shouldBe true
        }
}
