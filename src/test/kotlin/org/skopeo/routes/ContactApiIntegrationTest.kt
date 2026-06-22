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
import org.skopeo.dto.contact.ContactCreateRequest
import org.skopeo.dto.contact.ContactResponse
import org.skopeo.dto.contact.ContactUpdateRequest
import org.skopeo.dto.contact.VerificationRequest
import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.dto.user.NameDto
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

/**
 * End-to-end exercise of the contact API, focused on the core acceptance criterion:
 * an ADMINISTRATOR can mark a contact verified but the owning user cannot self-verify.
 */
class ContactApiIntegrationTest {
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

    /** Provision an ADMINISTRATOR directly (the API only ever grants PLAYER) and return its token. */
    private fun seedAdminToken(uid: String = "admin"): String {
        UserRepository().provision(
            ProvisionUserCommand(
                firebaseUid = uid,
                identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                names = listOf(UserName(type = NameType.FIRST, value = "Admin", isPrimary = true)),
                capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR),
            ),
        )
        return TestFirebaseAuth.mintToken(uid = uid)
    }

    private suspend fun HttpClient.provisionSelf(token: String): UserResponse =
        post("/api/v1/users") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CreateUserRequest(names = listOf(NameDto(type = "FIRST", value = "Juan", isPrimary = true))))
        }.body()

    private suspend fun HttpClient.addPhone(
        token: String,
        userId: String,
    ): HttpResponse =
        post("/api/v1/users/$userId/contacts") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(ContactCreateRequest(type = "PHONE", value = "+639170000000", isPrimary = true))
        }

    @Test
    fun `admin verifies a contact that the owner cannot self-verify`() =
        withApp { client ->
            val adminToken = seedAdminToken()
            val userToken = TestFirebaseAuth.mintToken(uid = "fb-1")
            val user = client.provisionSelf(userToken)

            val phone = client.addPhone(token = userToken, userId = user.id)
            phone.status shouldBe HttpStatusCode.Created
            val contact = phone.body<ContactResponse>()
            contact.status shouldBe "PENDING"

            // The owner cannot mark their own contact verified.
            client.put("/api/v1/users/${user.id}/contacts/${contact.id}/verification") {
                header(HttpHeaders.Authorization, "Bearer $userToken")
                contentType(ContentType.Application.Json)
                setBody(VerificationRequest(status = "VERIFIED"))
            }.status shouldBe HttpStatusCode.Forbidden

            // An administrator can.
            val verified =
                client.put("/api/v1/users/${user.id}/contacts/${contact.id}/verification") {
                    header(HttpHeaders.Authorization, "Bearer $adminToken")
                    contentType(ContentType.Application.Json)
                    setBody(VerificationRequest(status = "VERIFIED"))
                }
            verified.status shouldBe HttpStatusCode.OK
            verified.body<ContactResponse>().let {
                it.status shouldBe "VERIFIED"
                it.method shouldBe "ADMIN_OVERRIDE"
            }
        }

    @Test
    fun `duplicate type conflicts and editing the value resets verification`() =
        withApp { client ->
            val adminToken = seedAdminToken()
            val userToken = TestFirebaseAuth.mintToken(uid = "fb-2")
            val user = client.provisionSelf(userToken)
            val contact = client.addPhone(token = userToken, userId = user.id).body<ContactResponse>()

            // Second phone for the same user → one-per-type conflict.
            client.addPhone(token = userToken, userId = user.id).status shouldBe HttpStatusCode.Conflict

            // Verify it, then edit the value → status falls back to PENDING.
            client.put("/api/v1/users/${user.id}/contacts/${contact.id}/verification") {
                header(HttpHeaders.Authorization, "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody(VerificationRequest(status = "VERIFIED"))
            }.status shouldBe HttpStatusCode.OK

            val edited =
                client.patch("/api/v1/users/${user.id}/contacts/${contact.id}") {
                    header(HttpHeaders.Authorization, "Bearer $userToken")
                    contentType(ContentType.Application.Json)
                    setBody(ContactUpdateRequest(value = "+639180000000", isPrimary = true))
                }
            edited.status shouldBe HttpStatusCode.OK
            edited.body<ContactResponse>().status shouldBe "PENDING"
        }

    @Test
    fun `owner lists and deletes their contacts`() =
        withApp { client ->
            val userToken = TestFirebaseAuth.mintToken(uid = "fb-3")
            val user = client.provisionSelf(userToken)
            val contact = client.addPhone(token = userToken, userId = user.id).body<ContactResponse>()

            client.get("/api/v1/users/${user.id}/contacts") {
                header(HttpHeaders.Authorization, "Bearer $userToken")
            }.status shouldBe HttpStatusCode.OK

            client.delete("/api/v1/users/${user.id}/contacts/${contact.id}") {
                header(HttpHeaders.Authorization, "Bearer $userToken")
            }.status shouldBe HttpStatusCode.NoContent
        }
}
