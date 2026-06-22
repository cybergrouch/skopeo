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
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.dto.user.NameDto
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

    private val defaultBody = CreateUserRequest(names = listOf(NameDto(type = "FIRST", value = "Juan", isPrimary = true)))

    private suspend fun HttpClient.createUser(
        token: String,
        body: CreateUserRequest = defaultBody,
    ): HttpResponse =
        post("/api/v1/users") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
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

            val created = client.createUser(token)
            created.status shouldBe HttpStatusCode.Created
            val body = created.body<UserResponse>()
            body.firebaseUid shouldBe "fb-1"
            body.capabilities shouldBe listOf("PLAYER")

            val again = client.createUser(token)
            again.status shouldBe HttpStatusCode.OK
            again.body<UserResponse>().id shouldBe body.id
        }

    @Test
    fun `GET me is 404 before provisioning and 200 after`() =
        withApp { client ->
            val token = TestFirebaseAuth.mintToken(uid = "fb-2")

            client.get("/api/v1/users/me") { header(HttpHeaders.Authorization, "Bearer $token") }
                .status shouldBe HttpStatusCode.NotFound

            client.createUser(token)

            val me = client.get("/api/v1/users/me") { header(HttpHeaders.Authorization, "Bearer $token") }
            me.status shouldBe HttpStatusCode.OK
            me.body<UserResponse>().firebaseUid shouldBe "fb-2"
        }

    @Test
    fun `GET by id enforces self-access`() =
        withApp { client ->
            val aliceToken = TestFirebaseAuth.mintToken(uid = "alice")
            val bobToken = TestFirebaseAuth.mintToken(uid = "bob")
            val alice = client.createUser(aliceToken).body<UserResponse>()
            client.createUser(bobToken)

            client.get("/api/v1/users/${alice.id}") { header(HttpHeaders.Authorization, "Bearer $aliceToken") }
                .status shouldBe HttpStatusCode.OK
            client.get("/api/v1/users/${alice.id}") { header(HttpHeaders.Authorization, "Bearer $bobToken") }
                .status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `unknown id is 404 and malformed id is 400`() =
        withApp { client ->
            val token = TestFirebaseAuth.mintToken(uid = "fb-3")
            client.createUser(token)

            client.get("/api/v1/users/${java.util.UUID.randomUUID()}") { header(HttpHeaders.Authorization, "Bearer $token") }
                .status shouldBe HttpStatusCode.NotFound
            client.get("/api/v1/users/not-a-uuid") { header(HttpHeaders.Authorization, "Bearer $token") }
                .status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `PATCH updates and DELETE deactivates`() =
        withApp { client ->
            val token = TestFirebaseAuth.mintToken(uid = "fb-4")
            val user = client.createUser(token).body<UserResponse>()

            val patched =
                client.patch("/api/v1/users/${user.id}") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(ProfileRequest(city = "Cebu"))
                }
            patched.status shouldBe HttpStatusCode.OK
            patched.body<UserResponse>().city shouldBe "Cebu"

            client.put("/api/v1/users/${user.id}") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(ProfileRequest(city = "Davao"))
            }.body<UserResponse>().city shouldBe "Davao"

            client.delete("/api/v1/users/${user.id}") { header(HttpHeaders.Authorization, "Bearer $token") }
                .status shouldBe HttpStatusCode.NoContent

            client.get("/api/v1/users/me") { header(HttpHeaders.Authorization, "Bearer $token") }
                .body<UserResponse>().isActive shouldBe false
        }

    @Test
    fun `invalid profile input is rejected with 400`() =
        withApp { client ->
            val token = TestFirebaseAuth.mintToken(uid = "fb-5")

            client.createUser(token, CreateUserRequest(gender = "X")).status shouldBe HttpStatusCode.BadRequest
        }
}
