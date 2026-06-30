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
import org.skopeo.dto.capability.CapabilityGrantRequest
import org.skopeo.dto.capability.CapabilityResponse
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
 * End-to-end exercise of the capability API over the real Firebase JWT auth path: an admin
 * grants/revokes roles, and a normal user is refused the entire surface.
 */
class CapabilityApiIntegrationTest {
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

    /** Seed an ADMINISTRATOR directly (bootstrap) and return its token. */
    private fun seedAdminToken(uid: String = "admin"): String {
        UserRepository().provision(
            ProvisionUserCommand(
                firebaseUid = uid,
                identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                names = listOf(UserName(type = NameType.DISPLAY, value = "Admin")),
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

    @Test
    fun `an admin grants then revokes a role`() =
        withApp { client ->
            val adminToken = seedAdminToken()
            val userToken = TestFirebaseAuth.mintToken(uid = "fb-1")
            val user = client.provisionSelf(token = userToken)

            val granted =
                client.post(urlString = "/api/v1/users/${user.id}/capabilities") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = CapabilityGrantRequest(capability = "HOST"))
                }
            granted.status shouldBe HttpStatusCode.Created
            granted.body<CapabilityResponse>().capability shouldBe "HOST"

            val roles =
                client.get(urlString = "/api/v1/users/${user.id}/capabilities") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                }.body<List<CapabilityResponse>>()
            roles.any { it.capability == "HOST" && it.isActive } shouldBe true

            client.delete(urlString = "/api/v1/users/${user.id}/capabilities/HOST") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
            }.status shouldBe HttpStatusCode.NoContent
        }

    @Test
    fun `a normal user is refused the capability API`() =
        withApp { client ->
            seedAdminToken()
            val userToken = TestFirebaseAuth.mintToken(uid = "fb-2")
            val user = client.provisionSelf(token = userToken)

            client.get(urlString = "/api/v1/users/${user.id}/capabilities") {
                header(key = HttpHeaders.Authorization, value = "Bearer $userToken")
            }.status shouldBe HttpStatusCode.Forbidden

            client.post(urlString = "/api/v1/users/${user.id}/capabilities") {
                header(key = HttpHeaders.Authorization, value = "Bearer $userToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = CapabilityGrantRequest(capability = "ADMINISTRATOR"))
            }.status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `an invalid capability is rejected at the route with a 400 (#116)`() =
        withApp { client ->
            val adminToken = seedAdminToken()
            val user = client.provisionSelf(token = TestFirebaseAuth.mintToken(uid = "fb-3"))

            client.post(urlString = "/api/v1/users/${user.id}/capabilities") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = CapabilityGrantRequest(capability = "WIZARD"))
            }.status shouldBe HttpStatusCode.BadRequest

            client.delete(urlString = "/api/v1/users/${user.id}/capabilities/WIZARD") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
            }.status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `the last administrator cannot be revoked`() =
        withApp { client ->
            val adminToken = seedAdminToken()
            val admin = UserRepository().findByFirebaseUid(firebaseUid = "admin")!!

            client.delete(urlString = "/api/v1/users/${admin.id}/capabilities/ADMINISTRATOR") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
            }.status shouldBe HttpStatusCode.Conflict
        }
}
