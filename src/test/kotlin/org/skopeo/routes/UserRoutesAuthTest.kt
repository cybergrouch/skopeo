package org.skopeo.routes

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.skopeo.module
import kotlin.test.Test

/**
 * Verifies the Firebase JWT auth wiring protects user routes. Happy-path token
 * verification needs a real/emulated Firebase token and is covered separately;
 * these assert the gate rejects missing/invalid tokens (no network required).
 */
class UserRoutesAuthTest {
    @Test
    fun meRequiresAuthentication() =
        testApplication {
            application { module(initDatabase = false) }

            val response = client.get("/api/v1/users/me")

            response.status shouldBe HttpStatusCode.Unauthorized
        }

    @Test
    fun meRejectsMalformedToken() =
        testApplication {
            application { module(initDatabase = false) }

            val response =
                client.get("/api/v1/users/me") {
                    header(HttpHeaders.Authorization, "Bearer not-a-real-jwt")
                }

            response.status shouldBe HttpStatusCode.Unauthorized
            response.bodyAsText() shouldContain "Unauthorized"
        }
}
