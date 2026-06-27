// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.dto.name.NameCreateRequest
import org.skopeo.dto.name.NameResponse
import org.skopeo.dto.name.NameStateRequest
import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.dto.user.UserResponse
import org.skopeo.module
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestFirebaseAuth

/**
 * End-to-end exercise of the user-name API: append-only adds, multiple names of a type,
 * replacing the display name, the display name being undisable-able, and self-access — over
 * the real Firebase JWT auth path.
 */
class NameApiIntegrationTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient { install(ContentNegotiation) { json() } }

    private fun withApp(block: suspend (HttpClient) -> Unit) =
        testApplication {
            application { module(initDatabase = false, firebaseAuth = TestFirebaseAuth.settings) }
            block(jsonClient())
        }

    private suspend fun HttpClient.provisionSelf(token: String): UserResponse =
        post(urlString = "/api/v1/users") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
            contentType(type = ContentType.Application.Json)
            setBody(body = CreateUserRequest(displayName = "Juan", dateOfBirth = "2000-01-01", sex = "Male"))
        }.body()

    private suspend fun HttpClient.addName(
        token: String,
        userId: String,
        request: NameCreateRequest,
    ): HttpResponse =
        post(urlString = "/api/v1/users/$userId/names") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
            contentType(type = ContentType.Application.Json)
            setBody(body = request)
        }

    private suspend fun HttpClient.listNames(
        token: String,
        userId: String,
    ): List<NameResponse> =
        get(urlString = "/api/v1/users/$userId/names") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
        }.body()

    @Test
    fun `an invalid name type is rejected at the route with a 400 (#116)`() =
        withApp { client ->
            val token = TestFirebaseAuth.mintToken(uid = "fb-bad")
            val user = client.provisionSelf(token = token)

            client.addName(token = token, userId = user.id, request = NameCreateRequest(type = "ALIAS", value = "x"))
                .status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `add multiple names of a type and replace the display name`() =
        withApp { client ->
            val token = TestFirebaseAuth.mintToken(uid = "fb-1")
            val user = client.provisionSelf(token = token)

            // Provisioning created one DISPLAY name.
            client.listNames(token = token, userId = user.id).single().let {
                it.type shouldBe "DISPLAY"
                it.value shouldBe "Juan"
            }

            // Two nicknames of the same type are both accepted.
            client
                .addName(token = token, userId = user.id, request = NameCreateRequest(type = "NICKNAME", value = "JB"))
                .status shouldBe HttpStatusCode.Created
            client
                .addName(token = token, userId = user.id, request = NameCreateRequest(type = "NICKNAME", value = "Boy"))
                .status shouldBe HttpStatusCode.Created

            // Posting a new DISPLAY name replaces the old one.
            val newDisplay =
                client
                    .addName(token = token, userId = user.id, request = NameCreateRequest(type = "DISPLAY", value = "Johnny"))
                    .body<NameResponse>()
            newDisplay.value shouldBe "Johnny"

            val all = client.listNames(token = token, userId = user.id)
            all.count { it.type == "DISPLAY" && it.isActive } shouldBe 1
            all.single { it.type == "DISPLAY" && it.isActive }.value shouldBe "Johnny"
            all.count { it.type == "DISPLAY" && !it.isActive } shouldBe 1 // the old "Juan" kept as history
        }

    @Test
    fun `the display name cannot be disabled`() =
        withApp { client ->
            val token = TestFirebaseAuth.mintToken(uid = "fb-2")
            val user = client.provisionSelf(token = token)
            val display = client.listNames(token = token, userId = user.id).single { it.type == "DISPLAY" }

            client.put(urlString = "/api/v1/users/${user.id}/names/${display.id}/state") {
                header(key = HttpHeaders.Authorization, value = "Bearer $token")
                contentType(type = ContentType.Application.Json)
                setBody(body = NameStateRequest(isActive = false))
            }.status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `a non-owner cannot read another user's names`() =
        withApp { client ->
            val ownerToken = TestFirebaseAuth.mintToken(uid = "owner")
            val intruderToken = TestFirebaseAuth.mintToken(uid = "intruder")
            val owner = client.provisionSelf(token = ownerToken)
            client.provisionSelf(token = intruderToken)

            client.get(urlString = "/api/v1/users/${owner.id}/names") {
                header(key = HttpHeaders.Authorization, value = "Bearer $intruderToken")
            }.status shouldBe HttpStatusCode.Forbidden
        }
}
