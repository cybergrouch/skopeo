// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
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
import org.skopeo.dto.club.AssignOwnerRequest
import org.skopeo.dto.club.ClubResponse
import org.skopeo.dto.club.CreateClubRequest
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
import java.util.UUID

/** End-to-end exercise of the admin-only clubs API (#313). */
class ClubApiIntegrationTest {
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

    private suspend fun HttpClient.createClub(
        token: String,
        name: String,
    ) = post(urlString = "/api/v1/clubs") {
        header(key = HttpHeaders.Authorization, value = "Bearer $token")
        contentType(type = ContentType.Application.Json)
        setBody(body = CreateClubRequest(name = name))
    }

    @Test
    fun `an admin creates a club, assigns and removes an owner`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val adminToken = TestFirebaseAuth.mintToken(uid = "admin")
            val owner = seedUser(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))

            val created = client.createClub(token = adminToken, name = "Downtown TC")
            created.status shouldBe HttpStatusCode.Created
            val club = created.body<ClubResponse>()
            club.name shouldBe "Downtown TC"

            // Assign the owner.
            val assigned =
                client.post(urlString = "/api/v1/clubs/${club.id}/owners") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = AssignOwnerRequest(userId = owner.id.toString()))
                }
            assigned.status shouldBe HttpStatusCode.OK
            assigned.body<ClubResponse>().owners.single().userId shouldBe owner.id.toString()

            // It shows in the list.
            val list =
                client.get(urlString = "/api/v1/clubs") { header(key = HttpHeaders.Authorization, value = "Bearer $adminToken") }
            list.status shouldBe HttpStatusCode.OK
            list.body<List<ClubResponse>>() shouldHaveSize 1

            // Remove the owner.
            val removed =
                client.delete(urlString = "/api/v1/clubs/${club.id}/owners/${owner.id}") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                }
            removed.status shouldBe HttpStatusCode.OK
            removed.body<ClubResponse>().owners shouldHaveSize 0
        }

    @Test
    fun `a host cannot create clubs but may list them, a plain player cannot list (#313)`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            seedUser(uid = "player", roles = setOf(element = Capability.PLAYER))
            val hostToken = TestFirebaseAuth.mintToken(uid = "host")
            val playerToken = TestFirebaseAuth.mintToken(uid = "player")

            // Creating remains ADMINISTRATOR-only.
            client.createClub(token = hostToken, name = "Nope").status shouldBe HttpStatusCode.Forbidden
            // Listing is staff-readable (event creators pick a club), so a HOST may list…
            client.get(urlString = "/api/v1/clubs") { header(key = HttpHeaders.Authorization, value = "Bearer $hostToken") }
                .status shouldBe HttpStatusCode.OK
            // …but a plain player may not.
            client.get(urlString = "/api/v1/clubs") { header(key = HttpHeaders.Authorization, value = "Bearer $playerToken") }
                .status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `create rejects a blank name and assign to a missing club is 404`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val adminToken = TestFirebaseAuth.mintToken(uid = "admin")
            val owner = seedUser(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))

            client.createClub(token = adminToken, name = "   ").status shouldBe HttpStatusCode.BadRequest

            client.post(urlString = "/api/v1/clubs/${UUID.randomUUID()}/owners") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = AssignOwnerRequest(userId = owner.id.toString()))
            }.status shouldBe HttpStatusCode.NotFound
        }
}
