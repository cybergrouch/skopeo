// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import org.skopeo.dto.circuit.CircuitResponse
import org.skopeo.dto.circuit.CreateCircuitRequest
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.module
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestFirebaseAuth

/** End-to-end exercise of the admin-only circuits API (#525). */
class CircuitApiIntegrationTest {
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

    private fun seedUser(
        uid: String,
        roles: Set<Capability>,
    ): User =
        UserRepository().provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = roles,
                ),
        )

    private suspend fun HttpClient.createCircuit(
        token: String,
        name: String,
    ) = post(urlString = "/api/v1/circuits") {
        header(key = HttpHeaders.Authorization, value = "Bearer $token")
        contentType(type = ContentType.Application.Json)
        setBody(body = CreateCircuitRequest(name = name))
    }

    @Test
    fun `an admin creates a circuit and it appears in the list`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val adminToken = TestFirebaseAuth.mintToken(uid = "admin")

            val created = client.createCircuit(token = adminToken, name = "NORTH")
            created.status shouldBe HttpStatusCode.Created
            created.body<CircuitResponse>().name shouldBe "NORTH"

            val list =
                client.get(urlString = "/api/v1/circuits") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                }
            list.status shouldBe HttpStatusCode.OK
            list.body<List<CircuitResponse>>() shouldHaveSize 1
        }

    @Test
    fun `creating is forbidden for a non-admin and unauthorized without a token`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            val hostToken = TestFirebaseAuth.mintToken(uid = "host")

            client.createCircuit(token = hostToken, name = "NORTH").status shouldBe HttpStatusCode.Forbidden
            client.get(urlString = "/api/v1/circuits").status shouldBe HttpStatusCode.Unauthorized
        }
}
