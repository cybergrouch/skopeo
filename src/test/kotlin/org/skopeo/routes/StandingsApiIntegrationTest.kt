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
import org.skopeo.dto.standings.StandingsLocateResponse
import org.skopeo.dto.standings.StandingsPageResponse
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

/** End-to-end check of the paged standings serving layer (#220): paged reads, jump-to-me, and privacy. */
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

    private suspend fun signUp(
        client: HttpClient,
        uid: String,
        name: String,
    ): Pair<UserResponse, String> {
        val token = TestFirebaseAuth.mintToken(uid = uid)
        val user: UserResponse =
            client.post(urlString = "/api/v1/users") {
                header(key = HttpHeaders.Authorization, value = "Bearer $token")
                contentType(type = ContentType.Application.Json)
                setBody(body = CreateUserRequest(proposedRating = "4.0", displayName = name, dateOfBirth = "2000-01-01", sex = "Male"))
            }.body()
        return user to token
    }

    private suspend fun setRating(
        client: HttpClient,
        admin: String,
        userId: String,
        value: String,
    ) {
        client.put(urlString = "/api/v1/users/$userId/ratings") {
            header(key = HttpHeaders.Authorization, value = "Bearer $admin")
            contentType(type = ContentType.Application.Json)
            setBody(body = SetRatingRequest(value = value))
        }.status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `setting a rating publishes a snapshot the paged read serves, without leaking exact ratings`() =
        withApp { client ->
            val admin = adminToken()
            val (player, playerToken) = signUp(client = client, uid = "p1", name = "Player One")
            // Setting the rating triggers a standings rebuild → a PUBLISHED snapshot exists.
            setRating(client = client, admin = admin, userId = player.id, value = "4.2")

            val response =
                client.get(urlString = "/api/v1/standings?band=4.0&sex=Male") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $playerToken")
                }
            response.status shouldBe HttpStatusCode.OK
            val page: StandingsPageResponse = response.body()
            page.band shouldBe "4.0"
            page.label shouldContain "NTRP 4.0 Band Race"
            page.limit shouldBe 25
            page.total shouldBe 1
            page.entries.single().userId shouldBe player.id
            // The exact rating never leaks to a plain player (privacy).
            response.bodyAsText() shouldNotContain "4.200000"

            // An ADMINISTRATOR (or RATER) does see the precise rating (#186).
            val asAdmin: StandingsPageResponse =
                client.get(urlString = "/api/v1/standings?band=4.0&sex=Male") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $admin")
                }.body()
            asAdmin.entries.single().currentRating shouldBe "4.200000"
        }

    @Test
    fun `standings-me locates the caller and points at the containing page`() =
        withApp { client ->
            val admin = adminToken()
            val (player, playerToken) = signUp(client = client, uid = "p1", name = "Player One")
            setRating(client = client, admin = admin, userId = player.id, value = "4.2")

            val response =
                client.get(urlString = "/api/v1/standings/me") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $playerToken")
                }
            response.status shouldBe HttpStatusCode.OK
            val located: StandingsLocateResponse = response.body()
            located.band shouldBe "4.0"
            located.sex shouldBe "Male"
            located.rank shouldBe 1
            located.offset shouldBe 0
        }

    @Test
    fun `standings-me is 404 when the caller is not in the current standings`() =
        withApp { client ->
            val admin = adminToken()
            // A rated player exists (so a snapshot is published), but the caller is unrated.
            val (rated, _) = signUp(client = client, uid = "rated", name = "Rated")
            setRating(client = client, admin = admin, userId = rated.id, value = "4.2")
            val (_, unratedToken) = signUp(client = client, uid = "unrated", name = "Unrated")

            client.get(urlString = "/api/v1/standings/me") {
                header(key = HttpHeaders.Authorization, value = "Bearer $unratedToken")
            }.status shouldBe HttpStatusCode.NotFound
        }

    @Test
    fun `a rating change is reflected in a fresh page read (rebuild on change)`() =
        withApp { client ->
            val admin = adminToken()
            val (player, playerToken) = signUp(client = client, uid = "p1", name = "Player One")
            setRating(client = client, admin = admin, userId = player.id, value = "4.2") // FROM_4_0

            // A promotion into the 4.5 band; the read now serves the player there, not in 4.0.
            setRating(client = client, admin = admin, userId = player.id, value = "4.7") // FROM_4_5

            val old: StandingsPageResponse =
                client.get(urlString = "/api/v1/standings?band=4.0&sex=Male") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $playerToken")
                }.body()
            old.total shouldBe 0

            val current: StandingsPageResponse =
                client.get(urlString = "/api/v1/standings?band=4.5&sex=Male") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $playerToken")
                }.body()
            current.entries.single().userId shouldBe player.id
        }

    @Test
    fun `an unknown band code is a 400`() =
        withApp { client ->
            val playerToken = TestFirebaseAuth.mintToken(uid = "p1")
            client.get(urlString = "/api/v1/standings?band=nope") {
                header(key = HttpHeaders.Authorization, value = "Bearer $playerToken")
            }.status shouldBe HttpStatusCode.BadRequest
        }
}
