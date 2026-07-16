// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.skopeo.testsupport.TestFirebaseAuth

class CorsTest {
    /**
     * A CORS preflight for [method] from the dev web origin. The CORS plugin answers before routing:
     * 200 when the method is allowed, 403 when it isn't (which is what silently broke PATCH routes).
     */
    private fun preflightStatus(
        method: String,
        assert: suspend (io.ktor.client.statement.HttpResponse) -> Unit,
    ) = testApplication {
        application { module(initDatabase = false, firebaseAuth = TestFirebaseAuth.settings) }
        val response =
            client.options(urlString = "/api/v1/clubs/00000000-0000-0000-0000-000000000000") {
                header(key = HttpHeaders.Origin, value = "http://localhost:5173")
                header(key = HttpHeaders.AccessControlRequestMethod, value = method)
            }
        assert(response)
    }

    @Test
    fun `CORS preflight allows PATCH for a web origin (rename routes, #325)`() =
        preflightStatus(method = "PATCH") { response ->
            response.status shouldBe HttpStatusCode.OK
            response.headers[HttpHeaders.AccessControlAllowMethods].orEmpty() shouldContain "PATCH"
        }

    @Test
    fun `CORS preflight also allows the other mutating methods (POST, PUT, DELETE)`() {
        listOf("POST", "PUT", "DELETE").forEach { method ->
            preflightStatus(method = method) { response -> response.status shouldBe HttpStatusCode.OK }
        }
    }
}
