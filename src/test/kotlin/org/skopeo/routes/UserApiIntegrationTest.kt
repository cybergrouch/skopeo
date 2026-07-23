// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.dto.user.PhotoSettingsRequest
import org.skopeo.dto.user.ProfileRequest
import org.skopeo.dto.user.UserResponse
import org.skopeo.module
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestFirebaseAuth

/**
 * End-to-end exercise of the user API through the real Firebase JWT auth path:
 * tokens are minted by [TestFirebaseAuth] and verified by the production verifier
 * (pointed at a local key set), then routed to the service and a real PostgreSQL.
 */
class UserApiIntegrationTest {
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

    private val defaultBody = CreateUserRequest(proposedRating = "4.0", displayName = "Juan", dateOfBirth = "2000-01-01", sex = "Male")

    private suspend fun HttpClient.createUser(
        token: String,
        body: CreateUserRequest = defaultBody,
    ): HttpResponse =
        post(urlString = "/api/v1/users") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
            contentType(type = ContentType.Application.Json)
            setBody(body = body)
        }

    private fun withApp(block: suspend (HttpClient) -> Unit) =
        testApplication {
            application { module(initDatabase = false, firebaseAuth = TestFirebaseAuth.settings) }
            block(jsonClient())
        }

    @Test
    fun `POST provisions on first call and is idempotent`() =
        withApp { client ->
            val token =
                TestFirebaseAuth.mintToken(
                    uid = "fb-1",
                    email = "fb1@example.com",
                    emailVerified = true,
                    signInProvider = "google.com",
                )

            val created = client.createUser(token = token)
            created.status shouldBe HttpStatusCode.Created
            val body = created.body<UserResponse>()
            body.firebaseUid shouldBe "fb-1"
            // Every sign-up is a PLAYER and a RESEARCHER (#107).
            body.capabilities.toSet() shouldBe setOf("PLAYER", "RESEARCHER")

            val again = client.createUser(token = token)
            again.status shouldBe HttpStatusCode.OK
            again.body<UserResponse>().id shouldBe body.id
        }

    @Test
    fun `GET me is 404 before provisioning and 200 after`() =
        withApp { client ->
            val token = TestFirebaseAuth.mintToken(uid = "fb-2")

            client.get(urlString = "/api/v1/users/me") { header(key = HttpHeaders.Authorization, value = "Bearer $token") }
                .status shouldBe HttpStatusCode.NotFound

            client.createUser(token = token)

            val me = client.get(urlString = "/api/v1/users/me") { header(key = HttpHeaders.Authorization, value = "Bearer $token") }
            me.status shouldBe HttpStatusCode.OK
            me.body<UserResponse>().firebaseUid shouldBe "fb-2"
        }

    @Test
    fun `GET by id enforces self-access`() =
        withApp { client ->
            val aliceToken = TestFirebaseAuth.mintToken(uid = "alice")
            val bobToken = TestFirebaseAuth.mintToken(uid = "bob")
            val alice = client.createUser(token = aliceToken).body<UserResponse>()
            client.createUser(token = bobToken)

            client.get(urlString = "/api/v1/users/${alice.id}") { header(key = HttpHeaders.Authorization, value = "Bearer $aliceToken") }
                .status shouldBe HttpStatusCode.OK
            client.get(urlString = "/api/v1/users/${alice.id}") { header(key = HttpHeaders.Authorization, value = "Bearer $bobToken") }
                .status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `unknown id is 404 and malformed id is 400`() =
        withApp { client ->
            val token = TestFirebaseAuth.mintToken(uid = "fb-3")
            client.createUser(token = token)

            client.get(urlString = "/api/v1/users/${java.util.UUID.randomUUID()}") {
                header(key = HttpHeaders.Authorization, value = "Bearer $token")
            }
                .status shouldBe HttpStatusCode.NotFound
            client.get(urlString = "/api/v1/users/not-a-uuid") { header(key = HttpHeaders.Authorization, value = "Bearer $token") }
                .status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `PATCH updates self profile, a self DELETE is forbidden (admin-only, #518)`() =
        withApp { client ->
            val token = TestFirebaseAuth.mintToken(uid = "fb-4")
            val user = client.createUser(token = token).body<UserResponse>()

            val patched =
                client.patch(urlString = "/api/v1/users/${user.id}") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $token")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = ProfileRequest(city = "Cebu"))
                }
            patched.status shouldBe HttpStatusCode.OK
            patched.body<UserResponse>().city shouldBe "Cebu"

            client.put(urlString = "/api/v1/users/${user.id}") {
                header(key = HttpHeaders.Authorization, value = "Bearer $token")
                contentType(type = ContentType.Application.Json)
                setBody(body = ProfileRequest(city = "Davao"))
            }.body<UserResponse>().city shouldBe "Davao"

            // Deletion is ADMINISTRATOR-only (#518): a user cannot delete their own account.
            client.delete(urlString = "/api/v1/users/${user.id}") { header(key = HttpHeaders.Authorization, value = "Bearer $token") }
                .status shouldBe HttpStatusCode.Forbidden

            // The account is untouched — still active.
            client.get(urlString = "/api/v1/users/me") { header(key = HttpHeaders.Authorization, value = "Bearer $token") }
                .body<UserResponse>().isActive shouldBe true
        }

    @Test
    fun `PUT photo sets a custom URL, rejects a bad URL, and hides (#303)`() =
        withApp { client ->
            val token = TestFirebaseAuth.mintToken(uid = "fb-photo")
            val user = client.createUser(token = token).body<UserResponse>()

            val set =
                client.put(urlString = "/api/v1/users/${user.id}/photo") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $token")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = PhotoSettingsRequest(customPhotoUrl = "https://c/me.png", hidden = false))
                }
            set.status shouldBe HttpStatusCode.OK
            set.body<UserResponse>().let {
                it.customPhotoUrl shouldBe "https://c/me.png"
                it.photoUrl shouldBe "https://c/me.png"
                it.photoHidden shouldBe false
            }

            // A non-http(s) URL is rejected. Sent as raw JSON — the DTO's init validation would
            // otherwise throw client-side before the request is even made.
            client.put(urlString = "/api/v1/users/${user.id}/photo") {
                header(key = HttpHeaders.Authorization, value = "Bearer $token")
                setBody(body = TextContent(text = """{"customPhotoUrl":"ftp://nope/x.jpg"}""", contentType = ContentType.Application.Json))
            }.status shouldBe HttpStatusCode.BadRequest

            // Hiding suppresses the effective photo.
            client.put(urlString = "/api/v1/users/${user.id}/photo") {
                header(key = HttpHeaders.Authorization, value = "Bearer $token")
                contentType(type = ContentType.Application.Json)
                setBody(body = PhotoSettingsRequest(customPhotoUrl = "https://c/me.png", hidden = true))
            }.body<UserResponse>().photoUrl shouldBe null
        }

    @Test
    fun `invalid profile input is rejected with 400`() =
        withApp { client ->
            val token = TestFirebaseAuth.mintToken(uid = "fb-5")

            val badSex = CreateUserRequest(proposedRating = "4.0", displayName = "Juan", sex = "X", dateOfBirth = "2000-01-01")
            client.createUser(token = token, body = badSex).status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `sex and date of birth are required`() =
        withApp { client ->
            val token = TestFirebaseAuth.mintToken(uid = "fb-6")

            suspend fun postRaw(json: String) =
                client
                    .post(urlString = "/api/v1/users") {
                        header(key = HttpHeaders.Authorization, value = "Bearer $token")
                        setBody(body = TextContent(json, ContentType.Application.Json))
                    }.status

            postRaw(json = """{"displayName":"Juan","dateOfBirth":"2000-01-01"}""") shouldBe HttpStatusCode.BadRequest
            postRaw(json = """{"displayName":"Juan","sex":"Male"}""") shouldBe HttpStatusCode.BadRequest
        }
}
