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
import org.skopeo.common.security.Capability
import org.skopeo.dto.settings.AwardRankingPointsResponse
import org.skopeo.dto.settings.FacebookLoginResponse
import org.skopeo.dto.settings.SetAwardRankingPointsRequest
import org.skopeo.dto.settings.SetFacebookLoginRequest
import org.skopeo.mapper.entity.user.toDomain
import org.skopeo.model.AuthProvider
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.module
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestFirebaseAuth

/** End-to-end exercise of the Facebook-login feature flag API (#647): public read, admin-only write. */
class FeatureFlagApiIntegrationTest {
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
        ).toDomain()

    @Test
    fun `the facebook-login flag is publicly readable and defaults to enabled (#647)`() =
        withApp { client ->
            val anon = client.get(urlString = "/api/v1/settings/facebook-login")
            anon.status shouldBe HttpStatusCode.OK
            anon.body<FacebookLoginResponse>().enabled shouldBe true
        }

    @Test
    fun `an admin disables facebook login and the read reflects it (#647)`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val adminToken = TestFirebaseAuth.mintToken(uid = "admin")

            val updated =
                client.put(urlString = "/api/v1/settings/facebook-login") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = SetFacebookLoginRequest(enabled = false))
                }
            updated.status shouldBe HttpStatusCode.OK
            updated.body<FacebookLoginResponse>().enabled shouldBe false

            client.get(urlString = "/api/v1/settings/facebook-login").body<FacebookLoginResponse>().enabled shouldBe false
        }

    @Test
    fun `a plain player cannot set the facebook-login flag (#647)`() =
        withApp { client ->
            seedUser(uid = "player", roles = setOf(element = Capability.PLAYER))
            val playerToken = TestFirebaseAuth.mintToken(uid = "player")

            client.put(urlString = "/api/v1/settings/facebook-login") {
                header(key = HttpHeaders.Authorization, value = "Bearer $playerToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = SetFacebookLoginRequest(enabled = false))
            }.status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `the award-ranking-points flag is publicly readable and defaults to disabled (#641)`() =
        withApp { client ->
            val anon = client.get(urlString = "/api/v1/settings/award-ranking-points")
            anon.status shouldBe HttpStatusCode.OK
            anon.body<AwardRankingPointsResponse>().enabled shouldBe false
        }

    @Test
    fun `an admin enables award ranking points and the read reflects it (#641)`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val adminToken = TestFirebaseAuth.mintToken(uid = "admin")

            val updated =
                client.put(urlString = "/api/v1/settings/award-ranking-points") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = SetAwardRankingPointsRequest(enabled = true))
                }
            updated.status shouldBe HttpStatusCode.OK
            updated.body<AwardRankingPointsResponse>().enabled shouldBe true

            client.get(urlString = "/api/v1/settings/award-ranking-points")
                .body<AwardRankingPointsResponse>().enabled shouldBe true
        }

    @Test
    fun `a plain player cannot set the award-ranking-points flag (#641)`() =
        withApp { client ->
            seedUser(uid = "player", roles = setOf(element = Capability.PLAYER))
            val playerToken = TestFirebaseAuth.mintToken(uid = "player")

            client.put(urlString = "/api/v1/settings/award-ranking-points") {
                header(key = HttpHeaders.Authorization, value = "Bearer $playerToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = SetAwardRankingPointsRequest(enabled = true))
            }.status shouldBe HttpStatusCode.Forbidden
        }
}
