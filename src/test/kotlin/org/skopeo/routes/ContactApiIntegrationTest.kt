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
import org.skopeo.dto.contact.ContactStateRequest
import org.skopeo.dto.contact.VerificationRequest
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
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                    names = listOf(UserName(type = NameType.FIRST, value = "Admin")),
                    capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR),
                ),
        )
        return TestFirebaseAuth.mintToken(uid = uid)
    }

    private suspend fun HttpClient.provisionSelf(token: String): UserResponse =
        post(urlString = "/api/v1/users") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
            contentType(type = ContentType.Application.Json)
            setBody(body = CreateUserRequest(proposedRating = "4.0", displayName = "Juan", dateOfBirth = "2000-01-01", sex = "Male"))
        }.body()

    private suspend fun HttpClient.addPhone(
        token: String,
        userId: String,
    ): HttpResponse =
        post(urlString = "/api/v1/users/$userId/contacts") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
            contentType(type = ContentType.Application.Json)
            setBody(body = ContactCreateRequest(type = "PHONE", value = "+639170000000", isPrimary = true))
        }

    @Test
    fun `invalid contact type and verification status are rejected at the route with 400 (#116)`() =
        withApp { client ->
            val adminToken = seedAdminToken()
            val userToken = TestFirebaseAuth.mintToken(uid = "fb-bad")
            val user = client.provisionSelf(token = userToken)

            client.post(urlString = "/api/v1/users/${user.id}/contacts") {
                header(key = HttpHeaders.Authorization, value = "Bearer $userToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = ContactCreateRequest(type = "FAX", value = "x", isPrimary = true))
            }.status shouldBe HttpStatusCode.BadRequest

            val contact = client.addPhone(token = userToken, userId = user.id).body<ContactResponse>()
            client.put(urlString = "/api/v1/users/${user.id}/contacts/${contact.id}/verification") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = VerificationRequest(status = "MAYBE"))
            }.status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `admin verifies a contact that the owner cannot self-verify`() =
        withApp { client ->
            val adminToken = seedAdminToken()
            val userToken = TestFirebaseAuth.mintToken(uid = "fb-1")
            val user = client.provisionSelf(token = userToken)

            val phone = client.addPhone(token = userToken, userId = user.id)
            phone.status shouldBe HttpStatusCode.Created
            val contact = phone.body<ContactResponse>()
            contact.status shouldBe "PENDING"

            // The owner cannot mark their own contact verified.
            client.put(urlString = "/api/v1/users/${user.id}/contacts/${contact.id}/verification") {
                header(key = HttpHeaders.Authorization, value = "Bearer $userToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = VerificationRequest(status = "VERIFIED"))
            }.status shouldBe HttpStatusCode.Forbidden

            // An administrator can.
            val verified =
                client.put(urlString = "/api/v1/users/${user.id}/contacts/${contact.id}/verification") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = VerificationRequest(status = "VERIFIED"))
                }
            verified.status shouldBe HttpStatusCode.OK
            verified.body<ContactResponse>().let {
                it.status shouldBe "VERIFIED"
                it.method shouldBe "ADMIN_OVERRIDE"
            }
        }

    @Test
    fun `a second active contact conflicts, but disabling then adding works`() =
        withApp { client ->
            val userToken = TestFirebaseAuth.mintToken(uid = "fb-2")
            val user = client.provisionSelf(token = userToken)
            val contact = client.addPhone(token = userToken, userId = user.id).body<ContactResponse>()

            // Second active phone for the same user → one-active-per-type conflict.
            client.addPhone(token = userToken, userId = user.id).status shouldBe HttpStatusCode.Conflict

            // Disable the first, then a new phone is accepted.
            val disabled =
                client.put(urlString = "/api/v1/users/${user.id}/contacts/${contact.id}/state") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $userToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = ContactStateRequest(isActive = false))
                }
            disabled.status shouldBe HttpStatusCode.OK
            disabled.body<ContactResponse>().isActive shouldBe false

            client.addPhone(token = userToken, userId = user.id).status shouldBe HttpStatusCode.Created
        }

    @Test
    fun `owner lists and disables their contacts`() =
        withApp { client ->
            val userToken = TestFirebaseAuth.mintToken(uid = "fb-3")
            val user = client.provisionSelf(token = userToken)
            val contact = client.addPhone(token = userToken, userId = user.id).body<ContactResponse>()

            client.get(urlString = "/api/v1/users/${user.id}/contacts") {
                header(key = HttpHeaders.Authorization, value = "Bearer $userToken")
            }.status shouldBe HttpStatusCode.OK

            client.put(urlString = "/api/v1/users/${user.id}/contacts/${contact.id}/state") {
                header(key = HttpHeaders.Authorization, value = "Bearer $userToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = ContactStateRequest(isActive = false))
            }.status shouldBe HttpStatusCode.OK
        }
}
