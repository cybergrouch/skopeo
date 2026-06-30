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
import org.skopeo.dto.rating.RatingHistoryResponse
import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.dto.user.PlayerMatchHistoryEntry
import org.skopeo.dto.user.PublicPlayerResponse
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

/** End-to-end exercise of the auth-gated player-profile-by-code endpoint (issue #61). */
class PlayerApiIntegrationTest {
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

    private suspend fun HttpClient.createUser(token: String): HttpResponse =
        post(urlString = "/api/v1/users") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
            contentType(type = ContentType.Application.Json)
            setBody(body = CreateUserRequest(proposedRating = "4.0", displayName = "Ana", dateOfBirth = "2000-01-01", sex = "Male"))
        }

    private fun seedAdminToken(uid: String = "admin"): String {
        UserRepository().provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = "Admin")),
                    capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR),
                ),
        )
        return TestFirebaseAuth.mintToken(uid = uid)
    }

    @Test
    fun `any authenticated user can resolve a player's public profile by code`() =
        withApp { client ->
            val owner = client.createUser(token = TestFirebaseAuth.mintToken(uid = "owner")).body<UserResponse>()
            val viewerToken = TestFirebaseAuth.mintToken(uid = "viewer") // a different, unprovisioned caller

            val response =
                client.get(urlString = "/api/v1/players/${owner.publicCode.lowercase()}") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $viewerToken")
                }

            response.status shouldBe HttpStatusCode.OK
            val profile = response.body<PublicPlayerResponse>()
            profile.publicCode shouldBe owner.publicCode
            profile.displayName shouldBe "Ana"
        }

    @Test
    fun `an unknown code returns 404`() =
        withApp { client ->
            val token = TestFirebaseAuth.mintToken(uid = "u1")
            val response =
                client.get(urlString = "/api/v1/players/ZZZZZZ") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $token")
                }
            response.status shouldBe HttpStatusCode.NotFound
        }

    @Test
    fun `the public profile + match history are viewable anonymously; rating-history stays auth-required (#193)`() =
        withApp { client ->
            val owner = client.createUser(token = TestFirebaseAuth.mintToken(uid = "owner")).body<UserResponse>()

            // No Authorization header → the public profile and its match history still serve (optional auth).
            client.get(urlString = "/api/v1/players/${owner.publicCode}").status shouldBe HttpStatusCode.OK
            client.get(urlString = "/api/v1/players/${owner.publicCode}/match-history").status shouldBe HttpStatusCode.OK

            // The ADMINISTRATOR-only rating-history audit view remains auth-required.
            client.get(urlString = "/api/v1/players/${owner.publicCode}/rating-history").status shouldBe
                HttpStatusCode.Unauthorized
        }

    @Test
    fun `match history resolves by code and is empty for a player with no matches`() =
        withApp { client ->
            val owner = client.createUser(token = TestFirebaseAuth.mintToken(uid = "owner")).body<UserResponse>()
            val viewerToken = TestFirebaseAuth.mintToken(uid = "viewer")

            val response =
                client.get(urlString = "/api/v1/players/${owner.publicCode}/match-history") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $viewerToken")
                }

            response.status shouldBe HttpStatusCode.OK
            response.body<List<PlayerMatchHistoryEntry>>() shouldBe emptyList()
        }

    @Test
    fun `match history for an unknown code returns 404`() =
        withApp { client ->
            val token = TestFirebaseAuth.mintToken(uid = "u1")
            val response =
                client.get(urlString = "/api/v1/players/ZZZZZZ/match-history") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $token")
                }
            response.status shouldBe HttpStatusCode.NotFound
        }

    @Test
    fun `rating history by code is readable by an admin but forbidden to a plain player`() =
        withApp { client ->
            val adminToken = seedAdminToken()
            val player = client.createUser(token = TestFirebaseAuth.mintToken(uid = "player")).body<UserResponse>()

            val asAdmin =
                client.get(urlString = "/api/v1/players/${player.publicCode}/rating-history") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                }
            asAdmin.status shouldBe HttpStatusCode.OK
            asAdmin.body<List<RatingHistoryResponse>>() shouldBe emptyList() // no calculations yet

            // The player themselves is not an admin → forbidden on the code-based admin view.
            client.get(urlString = "/api/v1/players/${player.publicCode}/rating-history") {
                header(key = HttpHeaders.Authorization, value = "Bearer ${TestFirebaseAuth.mintToken(uid = "player")}")
            }.status shouldBe HttpStatusCode.Forbidden
        }
}
