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
import org.skopeo.dto.contact.ContactCreateRequest
import org.skopeo.dto.duplicate.ConfirmCandidateRequest
import org.skopeo.dto.duplicate.DuplicateCandidatePageResponse
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
 * End-to-end exercise of duplicate-account detection (#126): two users sharing a phone surface a
 * candidate to an admin, who confirms it (disabling the duplicate via #124); non-admins are refused.
 */
class DuplicateCandidateApiIntegrationTest {
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

    private fun seedAdminToken(uid: String = "admin"): String {
        UserRepository().provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = "Admin")),
                    capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR),
                ),
        )
        return TestFirebaseAuth.mintToken(uid = uid)
    }

    private suspend fun HttpClient.provisionSelf(uid: String): Pair<String, UserResponse> {
        val token = TestFirebaseAuth.mintToken(uid = uid)
        val user =
            post(urlString = "/api/v1/users") {
                header(key = HttpHeaders.Authorization, value = "Bearer $token")
                contentType(type = ContentType.Application.Json)
                setBody(body = CreateUserRequest(displayName = uid, dateOfBirth = "2000-01-01", sex = "Male"))
            }.body<UserResponse>()
        return token to user
    }

    private suspend fun HttpClient.addPhone(
        token: String,
        userId: String,
        value: String,
    ) = post(urlString = "/api/v1/users/$userId/contacts") {
        header(key = HttpHeaders.Authorization, value = "Bearer $token")
        contentType(type = ContentType.Application.Json)
        setBody(body = ContactCreateRequest(type = "PHONE", value = value, isPrimary = true))
    }

    private suspend fun HttpClient.openCandidates(adminToken: String): DuplicateCandidatePageResponse =
        get(urlString = "/api/v1/duplicate-candidates?status=OPEN") {
            header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
        }.body()

    @Test
    fun `a shared phone surfaces a candidate the admin can confirm`() =
        withApp { client ->
            val adminToken = seedAdminToken()
            val (tokenA, userA) = client.provisionSelf(uid = "alice")
            val (tokenB, userB) = client.provisionSelf(uid = "bob")
            client.addPhone(token = tokenA, userId = userA.id, value = "+639170000000").status shouldBe HttpStatusCode.Created
            // A formatting variant of the same number still matches — and the add succeeds.
            client.addPhone(token = tokenB, userId = userB.id, value = "+63 917 000 0000").status shouldBe HttpStatusCode.Created

            val candidate = client.openCandidates(adminToken = adminToken).items.single()
            setOf(candidate.userA.id, candidate.userB.id) shouldBe setOf(userA.id, userB.id)

            // Confirm keeping alice → bob is disabled (drops from search) and the candidate is resolved.
            client.post(urlString = "/api/v1/duplicate-candidates/${candidate.id}/confirm") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = ConfirmCandidateRequest(canonicalId = userA.id))
            }.status shouldBe HttpStatusCode.NoContent

            client.openCandidates(adminToken = adminToken).items.any { it.id == candidate.id } shouldBe false
            client.get(urlString = "/api/v1/users?name=bob") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
            }.body<List<org.skopeo.dto.user.UserSummaryResponse>>().any { it.id == userB.id } shouldBe false
        }

    @Test
    fun `a non-admin cannot read the candidate queue`() =
        withApp { client ->
            seedAdminToken()
            val (tokenA, _) = client.provisionSelf(uid = "alice")
            client.get(urlString = "/api/v1/duplicate-candidates") {
                header(key = HttpHeaders.Authorization, value = "Bearer $tokenA")
            }.status shouldBe HttpStatusCode.Forbidden
        }
}
