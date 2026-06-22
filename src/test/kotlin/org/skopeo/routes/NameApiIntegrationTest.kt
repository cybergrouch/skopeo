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
import org.skopeo.dto.name.NameCreateRequest
import org.skopeo.dto.name.NameResponse
import org.skopeo.dto.name.NameStateRequest
import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.dto.user.UserResponse
import org.skopeo.module
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestFirebaseAuth

/**
 * End-to-end exercise of the user-name API: append-only adds, multiple names of a type,
 * replacing the display name, the display name being undisable-able, and self-access — over
 * the real Firebase JWT auth path.
 */
class NameApiIntegrationTest {
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

    private suspend fun HttpClient.provisionSelf(token: String): UserResponse =
        post("/api/v1/users") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CreateUserRequest(displayName = "Juan"))
        }.body()

    private suspend fun HttpClient.addName(
        token: String,
        userId: String,
        request: NameCreateRequest,
    ): HttpResponse =
        post("/api/v1/users/$userId/names") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.listNames(
        token: String,
        userId: String,
    ): List<NameResponse> =
        get("/api/v1/users/$userId/names") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body()

    @Test
    fun `add multiple names of a type and replace the display name`() =
        withApp { client ->
            val token = TestFirebaseAuth.mintToken(uid = "fb-1")
            val user = client.provisionSelf(token)

            // Provisioning created one DISPLAY name.
            client.listNames(token, user.id).single().let {
                it.type shouldBe "DISPLAY"
                it.value shouldBe "Juan"
            }

            // Two nicknames of the same type are both accepted.
            client.addName(token, user.id, NameCreateRequest(type = "NICKNAME", value = "JB")).status shouldBe HttpStatusCode.Created
            client.addName(token, user.id, NameCreateRequest(type = "NICKNAME", value = "Boy")).status shouldBe HttpStatusCode.Created

            // Posting a new DISPLAY name replaces the old one.
            val newDisplay =
                client.addName(token, user.id, NameCreateRequest(type = "DISPLAY", value = "Johnny")).body<NameResponse>()
            newDisplay.value shouldBe "Johnny"

            val all = client.listNames(token, user.id)
            all.count { it.type == "DISPLAY" && it.isActive } shouldBe 1
            all.single { it.type == "DISPLAY" && it.isActive }.value shouldBe "Johnny"
            all.count { it.type == "DISPLAY" && !it.isActive } shouldBe 1 // the old "Juan" kept as history
        }

    @Test
    fun `the display name cannot be disabled`() =
        withApp { client ->
            val token = TestFirebaseAuth.mintToken(uid = "fb-2")
            val user = client.provisionSelf(token)
            val display = client.listNames(token, user.id).single { it.type == "DISPLAY" }

            client.put("/api/v1/users/${user.id}/names/${display.id}/state") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(NameStateRequest(isActive = false))
            }.status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `a non-owner cannot read another user's names`() =
        withApp { client ->
            val ownerToken = TestFirebaseAuth.mintToken(uid = "owner")
            val intruderToken = TestFirebaseAuth.mintToken(uid = "intruder")
            val owner = client.provisionSelf(ownerToken)
            client.provisionSelf(intruderToken)

            client.get("/api/v1/users/${owner.id}/names") {
                header(HttpHeaders.Authorization, "Bearer $intruderToken")
            }.status shouldBe HttpStatusCode.Forbidden
        }
}
