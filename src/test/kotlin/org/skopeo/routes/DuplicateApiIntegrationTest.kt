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
import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.dto.user.MarkDuplicatesRequest
import org.skopeo.dto.user.PublicPlayerResponse
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
 * End-to-end exercise of duplicate-profile rectification (#124): an admin marks a duplicate, it drops
 * out of search, its public profile links to the canonical, a returning login is blocked, then restore.
 */
class DuplicateApiIntegrationTest {
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

    private suspend fun HttpClient.provisionNamed(
        uid: String,
        displayName: String,
    ): UserResponse =
        post(urlString = "/api/v1/users") {
            header(key = HttpHeaders.Authorization, value = "Bearer ${TestFirebaseAuth.mintToken(uid = uid)}")
            contentType(type = ContentType.Application.Json)
            setBody(body = CreateUserRequest(proposedRating = "4.0", displayName = displayName, dateOfBirth = "2000-01-01", sex = "Male"))
        }.body()

    private suspend fun HttpClient.searchByName(
        token: String,
        name: String,
    ): List<UserSummaryResponse> =
        get(urlString = "/api/v1/users?name=$name") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
        }.body()

    @Test
    fun `mark disables and links the duplicate, blocks its login, and restore brings it back`() =
        withApp { client ->
            val adminToken = seedAdminToken()
            val canonical = client.provisionNamed(uid = "keep", displayName = "Realeza")
            val duplicate = client.provisionNamed(uid = "dup", displayName = "Dupliton")

            // Visible in search before marking.
            client.searchByName(token = adminToken, name = "Dupliton").any { it.id == duplicate.id } shouldBe true

            val marked =
                client.post(urlString = "/api/v1/users/${canonical.id}/duplicates") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = MarkDuplicatesRequest(duplicateIds = listOf(element = duplicate.id)))
                }
            marked.status shouldBe HttpStatusCode.OK

            // Dropped from search (it's inactive now).
            client.searchByName(token = adminToken, name = "Dupliton").any { it.id == duplicate.id } shouldBe false

            // Public profile renders the merged state with a link to the canonical.
            val profile =
                client.get(urlString = "/api/v1/players/${duplicate.publicCode}") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                }.body<PublicPlayerResponse>()
            profile.isDisabled shouldBe true
            profile.canonical?.publicCode shouldBe canonical.publicCode

            // A returning login of the disabled duplicate is blocked (403) with the canonical code.
            val relogin =
                client.post(urlString = "/api/v1/users") {
                    header(key = HttpHeaders.Authorization, value = "Bearer ${TestFirebaseAuth.mintToken(uid = "dup")}")
                    contentType(type = ContentType.Application.Json)
                    setBody(
                        body =
                            CreateUserRequest(
                                proposedRating = "4.0",
                                displayName = "Dupliton",
                                dateOfBirth = "2000-01-01",
                                sex = "Male",
                            ),
                    )
                }
            relogin.status shouldBe HttpStatusCode.Forbidden
            relogin.body<Map<String, String>>()["canonicalCode"] shouldBe canonical.publicCode

            // Restore brings it back into search.
            client.delete(urlString = "/api/v1/users/${duplicate.id}/duplicate") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
            }.status shouldBe HttpStatusCode.NoContent
            client.searchByName(token = adminToken, name = "Dupliton").any { it.id == duplicate.id } shouldBe true
        }

    @Test
    fun `a non-admin cannot mark duplicates`() =
        withApp { client ->
            seedAdminToken()
            val canonical = client.provisionNamed(uid = "keep", displayName = "Realeza")
            val duplicate = client.provisionNamed(uid = "dup", displayName = "Dupliton")

            client.post(urlString = "/api/v1/users/${canonical.id}/duplicates") {
                header(key = HttpHeaders.Authorization, value = "Bearer ${TestFirebaseAuth.mintToken(uid = "dup")}")
                contentType(type = ContentType.Application.Json)
                setBody(body = MarkDuplicatesRequest(duplicateIds = listOf(element = duplicate.id)))
            }.status shouldBe HttpStatusCode.Forbidden
        }
}
