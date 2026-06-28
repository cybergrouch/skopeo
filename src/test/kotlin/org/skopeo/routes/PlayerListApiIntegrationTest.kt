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
import org.skopeo.dto.seeding.AddMemberRequest
import org.skopeo.dto.seeding.CreatePlayerListRequest
import org.skopeo.dto.seeding.PlayerListResponse
import org.skopeo.dto.seeding.PlayerListSummaryResponse
import org.skopeo.dto.seeding.SeedingResponse
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

/** End-to-end exercise of the host seeding API (#111): create a list, add a rated player, seed, read back. */
class PlayerListApiIntegrationTest {
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

    private fun seedStaff(
        uid: String,
        roles: Set<Capability>,
    ): String {
        UserRepository().provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = roles + Capability.PLAYER,
                ),
        )
        return TestFirebaseAuth.mintToken(uid = uid)
    }

    private suspend fun HttpClient.provisionPlayer(uid: String): UserResponse =
        post(urlString = "/api/v1/users") {
            header(key = HttpHeaders.Authorization, value = "Bearer ${TestFirebaseAuth.mintToken(uid = uid)}")
            contentType(type = ContentType.Application.Json)
            setBody(body = CreateUserRequest(displayName = uid, dateOfBirth = "2000-01-01", sex = "Male"))
        }.body()

    @Test
    fun `a host curates a list, adds a rated player, and generates a seeding`() =
        withApp { client ->
            val host = seedStaff(uid = "host", roles = setOf(element = Capability.HOST))
            val admin = seedStaff(uid = "admin", roles = setOf(element = Capability.ADMINISTRATOR))
            val player = client.provisionPlayer(uid = "p1")
            client.put(urlString = "/api/v1/users/${player.id}/ratings") {
                header(key = HttpHeaders.Authorization, value = "Bearer $admin")
                contentType(type = ContentType.Application.Json)
                setBody(body = SetRatingRequest(value = "4.0"))
            }.status shouldBe HttpStatusCode.OK

            // Create the list.
            val created =
                client.post(urlString = "/api/v1/player-lists") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $host")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = CreatePlayerListRequest(name = "Club Open"))
                }
            created.status shouldBe HttpStatusCode.Created
            val list = created.body<PlayerListSummaryResponse>()
            list.memberCount shouldBe 0

            // Add the player.
            client.post(urlString = "/api/v1/player-lists/${list.id}/members") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
                contentType(type = ContentType.Application.Json)
                setBody(body = AddMemberRequest(userId = player.id))
            }.status shouldBe HttpStatusCode.NoContent

            // The detail now lists the member.
            val detail =
                client.get(urlString = "/api/v1/player-lists/${list.id}") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $host")
                }
            detail.status shouldBe HttpStatusCode.OK
            detail.body<PlayerListResponse>().members.single().id shouldBe player.id

            // Generate the seeding: one rated member → seed 1.
            val generated =
                client.post(urlString = "/api/v1/player-lists/${list.id}/seeding") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $host")
                }
            generated.status shouldBe HttpStatusCode.OK
            val seeding = generated.body<SeedingResponse>()
            seeding.entries.single().let {
                it.seed shouldBe 1
                it.position shouldBe 1
                it.userId shouldBe player.id
                it.ntrpBand shouldBe "4.0"
            }

            // And it reads back.
            client.get(urlString = "/api/v1/player-lists/${list.id}/seeding") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
            }.body<SeedingResponse>().entries.single().userId shouldBe player.id
        }

    @Test
    fun `a plain player cannot manage seeding lists`() =
        withApp { client ->
            val playerToken = TestFirebaseAuth.mintToken(uid = "pp")
            client.provisionPlayer(uid = "pp")

            client.post(urlString = "/api/v1/player-lists") {
                header(key = HttpHeaders.Authorization, value = "Bearer $playerToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = CreatePlayerListRequest(name = "Nope"))
            }.status shouldBe HttpStatusCode.Forbidden

            client.get(urlString = "/api/v1/player-lists") {
                header(key = HttpHeaders.Authorization, value = "Bearer $playerToken")
            }.status shouldBe HttpStatusCode.Forbidden
        }
}
