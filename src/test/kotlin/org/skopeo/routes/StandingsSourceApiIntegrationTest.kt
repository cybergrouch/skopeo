// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
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
import org.skopeo.dto.settings.SetStandingsSourceRequest
import org.skopeo.dto.settings.StandingsSourceResponse
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

/** End-to-end exercise of the standings-source API (#146): readable, admin-only write, flip-able RATING↔POINTS. */
class StandingsSourceApiIntegrationTest {
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

    @Test
    fun `the standings source is readable and defaults to RATING (#146)`() =
        withApp { client ->
            val anon = client.get(urlString = "/api/v1/settings/standings-source")
            anon.status shouldBe HttpStatusCode.OK
            anon.body<StandingsSourceResponse>().source shouldBe "RATING"
        }

    @Test
    fun `an admin sets the standings source and the read reflects it (#146)`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val adminToken = TestFirebaseAuth.mintToken(uid = "admin")

            val updated =
                client.put(urlString = "/api/v1/settings/standings-source") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = SetStandingsSourceRequest(source = "POINTS"))
                }
            updated.status shouldBe HttpStatusCode.OK
            updated.body<StandingsSourceResponse>().source shouldBe "POINTS"

            client.get(urlString = "/api/v1/settings/standings-source").body<StandingsSourceResponse>().source shouldBe "POINTS"
        }

    @Test
    fun `a plain player cannot set the standings source (#146)`() =
        withApp { client ->
            seedUser(uid = "player", roles = setOf(element = Capability.PLAYER))
            val playerToken = TestFirebaseAuth.mintToken(uid = "player")

            client.put(urlString = "/api/v1/settings/standings-source") {
                header(key = HttpHeaders.Authorization, value = "Bearer $playerToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = SetStandingsSourceRequest(source = "POINTS"))
            }.status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `setting an unknown standings source is a 400 (#146)`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val adminToken = TestFirebaseAuth.mintToken(uid = "admin")

            client.put(urlString = "/api/v1/settings/standings-source") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = SetStandingsSourceRequest(source = "GRAVEL"))
            }.status shouldBe HttpStatusCode.BadRequest
        }
}
