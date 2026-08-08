// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.nulls.shouldBeNull
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
import org.skopeo.common.dto.rating.SetRatingRequest
import org.skopeo.common.dto.seeding.AddMemberRequest
import org.skopeo.common.dto.seeding.CreatePlayerListRequest
import org.skopeo.common.dto.seeding.PlayerListResponse
import org.skopeo.common.dto.seeding.PlayerListSummaryResponse
import org.skopeo.common.dto.seeding.SaveSeedingOrderRequest
import org.skopeo.common.dto.seeding.SeedingResponse
import org.skopeo.common.dto.user.CreateUserRequest
import org.skopeo.common.dto.user.UserResponse
import org.skopeo.common.security.Capability
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
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
            setBody(body = CreateUserRequest(proposedRating = "4.0", displayName = uid, dateOfBirth = "2000-01-01", sex = "Male"))
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
                // Raw rating is ADMINISTRATOR-only (#583): a non-admin HOST gets the band + seed, not the value.
                it.rating.shouldBeNull()
            }

            // And it reads back (still band-only for the non-admin host, #583).
            client.get(urlString = "/api/v1/player-lists/${list.id}/seeding") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
            }.body<SeedingResponse>().entries.single().let {
                it.userId shouldBe player.id
                it.rating.shouldBeNull()
            }
        }

    @Test
    fun `a host drag-reorders and saves the seeding, renumbering seeds and flagging it manually edited`() =
        withApp { client ->
            val host = seedStaff(uid = "host", roles = setOf(element = Capability.HOST))
            val admin = seedStaff(uid = "admin", roles = setOf(element = Capability.ADMINISTRATOR))
            val p1 = client.provisionPlayer(uid = "p1")
            val p2 = client.provisionPlayer(uid = "p2")
            client.put(urlString = "/api/v1/users/${p1.id}/ratings") {
                header(key = HttpHeaders.Authorization, value = "Bearer $admin")
                contentType(type = ContentType.Application.Json)
                setBody(body = SetRatingRequest(value = "4.5"))
            }.status shouldBe HttpStatusCode.OK
            client.put(urlString = "/api/v1/users/${p2.id}/ratings") {
                header(key = HttpHeaders.Authorization, value = "Bearer $admin")
                contentType(type = ContentType.Application.Json)
                setBody(body = SetRatingRequest(value = "3.5"))
            }.status shouldBe HttpStatusCode.OK

            val list =
                client.post(urlString = "/api/v1/player-lists") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $host")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = CreatePlayerListRequest(name = "Club Open"))
                }.body<PlayerListSummaryResponse>()
            for (id in listOf(p1.id, p2.id)) {
                client.post(urlString = "/api/v1/player-lists/${list.id}/members") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $host")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = AddMemberRequest(userId = id))
                }.status shouldBe HttpStatusCode.NoContent
            }

            // Generated order is rating-desc (p1, p2) and not manually edited.
            val generated =
                client.post(urlString = "/api/v1/player-lists/${list.id}/seeding") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $host")
                }.body<SeedingResponse>()
            generated.entries.map { it.userId } shouldBe listOf(p1.id, p2.id)
            generated.manuallyEdited shouldBe false

            // Save a reversed hand order (p2, p1); seeds renumber 1..N by position and the flag flips.
            val saved =
                client.put(urlString = "/api/v1/player-lists/${list.id}/seeding") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $host")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = SaveSeedingOrderRequest(userIds = listOf(p2.id, p1.id)))
                }
            saved.status shouldBe HttpStatusCode.OK
            saved.body<SeedingResponse>().let {
                it.entries.map { e -> e.userId } shouldBe listOf(p2.id, p1.id)
                it.entries.map { e -> e.seed } shouldBe listOf(1, 2)
                it.manuallyEdited shouldBe true
            }

            // A read reflects the saved order + flag.
            client.get(urlString = "/api/v1/player-lists/${list.id}/seeding") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
            }.body<SeedingResponse>().let {
                it.entries.map { e -> e.userId } shouldBe listOf(p2.id, p1.id)
                it.manuallyEdited shouldBe true
            }
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
