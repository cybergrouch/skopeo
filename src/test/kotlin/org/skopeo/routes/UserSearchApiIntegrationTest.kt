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
import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.dto.user.UserResponse
import org.skopeo.dto.user.UserSummaryResponse
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

/**
 * End-to-end exercise of user search: staff (HOST/ADMINISTRATOR) can find users by name
 * (case-insensitive, partial); a plain player is refused; a missing query is a 400.
 */
class UserSearchApiIntegrationTest {
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

    private fun seedStaff(
        uid: String,
        roles: Set<Capability>,
    ): String {
        UserRepository().provision(
            ProvisionUserCommand(
                firebaseUid = uid,
                identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                names = listOf(UserName(type = NameType.DISPLAY, value = uid)),
                capabilities = roles + Capability.PLAYER,
            ),
        )
        return TestFirebaseAuth.mintToken(uid = uid)
    }

    private suspend fun HttpClient.provisionNamed(
        uid: String,
        displayName: String,
    ): UserResponse =
        post("/api/v1/users") {
            header(HttpHeaders.Authorization, "Bearer ${TestFirebaseAuth.mintToken(uid)}")
            contentType(ContentType.Application.Json)
            setBody(CreateUserRequest(displayName = displayName))
        }.body()

    private suspend fun HttpClient.lookup(
        token: String,
        params: String,
    ) = get("/api/v1/users${if (params.isEmpty()) "" else "?$params"}") {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    @Test
    fun `a host finds users by partial, case-insensitive name`() =
        withApp { client ->
            val host = seedStaff("host", setOf(Capability.HOST))
            client.provisionNamed("u1", "Alice")
            client.provisionNamed("u2", "Alicia")
            client.provisionNamed("u3", "Bob")

            val results = client.lookup(host, "name=ALI").body<List<UserSummaryResponse>>()

            results.map { it.displayName }.toSet() shouldBe setOf("Alice", "Alicia")
        }

    @Test
    fun `name search tolerates a misspelling`() =
        withApp { client ->
            val host = seedStaff("host", setOf(Capability.HOST))
            client.provisionNamed("u1", "Alice")
            client.provisionNamed("u2", "Bob")

            val results = client.lookup(host, "name=Alyce").body<List<UserSummaryResponse>>()

            results.map { it.displayName } shouldBe listOf("Alice")
        }

    @Test
    fun `a profile with several matching names appears once, and non-display names are searched`() =
        withApp { client ->
            val host = seedStaff("host", setOf(Capability.HOST))
            // One profile whose FIRST and DISPLAY names both match "john".
            UserRepository().provision(
                ProvisionUserCommand(
                    firebaseUid = "multi",
                    identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = "multi", isPrimary = true),
                    names =
                        listOf(
                            UserName(type = NameType.FIRST, value = "Johnathan"),
                            UserName(type = NameType.DISPLAY, value = "Johnny"),
                        ),
                    capabilities = setOf(Capability.PLAYER),
                ),
            )

            val results = client.lookup(host, "name=john").body<List<UserSummaryResponse>>()

            results.count { it.displayName == "Johnny" } shouldBe 1
        }

    @Test
    fun `an admin can search too`() =
        withApp { client ->
            val admin = seedStaff("admin", setOf(Capability.ADMINISTRATOR))
            client.provisionNamed("u1", "Charlie")

            val response = client.lookup(admin, "name=char")
            response.status shouldBe HttpStatusCode.OK
            response.body<List<UserSummaryResponse>>().single().displayName shouldBe "Charlie"
        }

    @Test
    fun `a host resolves known ids to summaries`() =
        withApp { client ->
            val host = seedStaff("host", setOf(Capability.HOST))
            val alice = client.provisionNamed("u1", "Alice")
            val bob = client.provisionNamed("u2", "Bob")
            client.provisionNamed("u3", "Carol")

            val results =
                client
                    .lookup(host, "ids=${alice.id},${bob.id}")
                    .body<List<UserSummaryResponse>>()

            results.map { it.displayName }.toSet() shouldBe setOf("Alice", "Bob")
        }

    @Test
    fun `a plain player cannot look up users`() =
        withApp { client ->
            seedStaff("admin", setOf(Capability.ADMINISTRATOR))
            val player = TestFirebaseAuth.mintToken("p1")
            client.provisionNamed("p1", "Player One")

            client.lookup(player, "name=player").status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `an unprovisioned caller is forbidden`() =
        withApp { client ->
            // A valid token whose uid was never provisioned — no caller record at all.
            client.lookup(TestFirebaseAuth.mintToken("ghost"), "name=alice").status shouldBe
                HttpStatusCode.Forbidden
        }

    @Test
    fun `an empty ids list is a 400`() =
        withApp { client ->
            val host = seedStaff("host", setOf(Capability.HOST))
            client.lookup(host, "ids=").status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `neither name nor ids is a 400`() =
        withApp { client ->
            val host = seedStaff("host", setOf(Capability.HOST))
            client.lookup(host, "").status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `both name and ids is a 400`() =
        withApp { client ->
            val host = seedStaff("host", setOf(Capability.HOST))
            client.lookup(host, "name=a&ids=${java.util.UUID.randomUUID()}").status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `a blank name is a 400`() =
        withApp { client ->
            val host = seedStaff("host", setOf(Capability.HOST))
            client.lookup(host, "name=%20").status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `a malformed id is a 400`() =
        withApp { client ->
            val host = seedStaff("host", setOf(Capability.HOST))
            client.lookup(host, "ids=not-a-uuid").status shouldBe HttpStatusCode.BadRequest
        }
}
