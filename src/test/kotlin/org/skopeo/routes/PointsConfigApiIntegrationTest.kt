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
import org.skopeo.contract.OpenPlayPointsConfig
import org.skopeo.contract.TournamentPointsConfig
import org.skopeo.dto.settings.OpenPlayConfigResponse
import org.skopeo.dto.settings.TournamentConfigResponse
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

/** End-to-end exercise of the points-config API (#552/#553): readable by users, admin-only writes. */
class PointsConfigApiIntegrationTest {
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
    fun `a signed-in user reads the default open-play schedule (#553)`() =
        withApp { client ->
            seedUser(uid = "player", roles = setOf(element = Capability.PLAYER))
            val playerToken = TestFirebaseAuth.mintToken(uid = "player")
            val response =
                client.get(urlString = "/api/v1/settings/points/open-play") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $playerToken")
                }
            response.status shouldBe HttpStatusCode.OK
            response.body<OpenPlayConfigResponse>().config.maxMargin shouldBe 6
        }

    @Test
    fun `an admin sets the tournament schedule and the read reflects it (#552)`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val adminToken = TestFirebaseAuth.mintToken(uid = "admin")
            val config =
                TournamentPointsConfig(sanctioned = listOf(100, 70, 50, 35), unsanctioned = listOf(50, 35, 25, 18), validityDays = 365)

            val updated =
                client.put(urlString = "/api/v1/settings/points/tournament") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = config)
                }
            updated.status shouldBe HttpStatusCode.OK
            updated.body<TournamentConfigResponse>().config.sanctioned shouldBe listOf(100, 70, 50, 35)

            client.get(urlString = "/api/v1/settings/points/tournament") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
            }.body<TournamentConfigResponse>().config.sanctioned shouldBe listOf(100, 70, 50, 35)
        }

    @Test
    fun `a plain player cannot set the tournament schedule (#552)`() =
        withApp { client ->
            seedUser(uid = "player", roles = setOf(element = Capability.PLAYER))
            val playerToken = TestFirebaseAuth.mintToken(uid = "player")
            val config =
                TournamentPointsConfig(sanctioned = listOf(80, 60, 40, 30), unsanctioned = listOf(40, 30, 20, 15), validityDays = 365)

            client.put(urlString = "/api/v1/settings/points/tournament") {
                header(key = HttpHeaders.Authorization, value = "Bearer $playerToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = config)
            }.status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `an admin sets the open-play schedule and the read reflects it (#553)`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val adminToken = TestFirebaseAuth.mintToken(uid = "admin")
            // Adopt the study's Seasonal open-play validity (3 months) by editing config.
            val config = OpenPlayPointsConfig.DEFAULT.copy(validityDays = 91)

            val updated =
                client.put(urlString = "/api/v1/settings/points/open-play") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = config)
                }
            updated.status shouldBe HttpStatusCode.OK
            updated.body<OpenPlayConfigResponse>().config.validityDays shouldBe 91

            client.get(urlString = "/api/v1/settings/points/open-play") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
            }.body<OpenPlayConfigResponse>().config.validityDays shouldBe 91
        }

    @Test
    fun `a plain player cannot set the open-play schedule (#553)`() =
        withApp { client ->
            seedUser(uid = "player", roles = setOf(element = Capability.PLAYER))
            val playerToken = TestFirebaseAuth.mintToken(uid = "player")

            client.put(urlString = "/api/v1/settings/points/open-play") {
                header(key = HttpHeaders.Authorization, value = "Bearer $playerToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = OpenPlayPointsConfig.DEFAULT)
            }.status shouldBe HttpStatusCode.Forbidden
        }
}
