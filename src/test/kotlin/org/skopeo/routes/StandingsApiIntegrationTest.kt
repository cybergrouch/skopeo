// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
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
import org.skopeo.dto.rating.SetRatingRequest
import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.dto.user.UserResponse
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.module
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestFirebaseAuth

/** End-to-end check of the per-band standings (#113): any player can read them, and no exact rating leaks. */
class StandingsApiIntegrationTest {
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

    private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient { install(plugin = ContentNegotiation) { json() } }

    private fun withApp(block: suspend (HttpClient) -> Unit) =
        testApplication {
            application { module(initDatabase = false, firebaseAuth = TestFirebaseAuth.settings) }
            block(jsonClient())
        }

    private fun adminToken(): String {
        UserRepository().provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = "admin",
                    identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = "admin", isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = "Admin")),
                    capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR),
                ),
        )
        return TestFirebaseAuth.mintToken(uid = "admin")
    }

    @Test
    fun `a plain player reads the per-band standings without seeing exact ratings`() =
        withApp { client ->
            val admin = adminToken()
            val playerToken = TestFirebaseAuth.mintToken(uid = "p1")
            val player: UserResponse =
                client.post(urlString = "/api/v1/users") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $playerToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = CreateUserRequest(displayName = "Player One", dateOfBirth = "2000-01-01", sex = "Male"))
                }.body()
            client.put(urlString = "/api/v1/users/${player.id}/ratings") {
                header(key = HttpHeaders.Authorization, value = "Bearer $admin")
                contentType(type = ContentType.Application.Json)
                setBody(body = SetRatingRequest(value = "4.2"))
            }.status shouldBe HttpStatusCode.OK

            val response =
                client.get(urlString = "/api/v1/standings") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $playerToken")
                }
            response.status shouldBe HttpStatusCode.OK

            // The 4.0–4.5 band lists the player; the exact rating (4.200000) never appears (privacy).
            val raw = response.bodyAsText()
            raw shouldContain "4.0–4.5"
            raw shouldContain player.id
            raw shouldNotContain "4.200000"
        }
}
