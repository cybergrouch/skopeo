// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import org.skopeo.dto.audit.AuditCommentRequest
import org.skopeo.dto.audit.AuditLogResponse
import org.skopeo.dto.rating.SetRatingRequest
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

/** End-to-end exercise of the admin trace viewer's read API (issue #102). */
class AuditApiIntegrationTest {
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

    private fun seedAdmin(uid: String): String {
        UserRepository().provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR),
                ),
        )
        return TestFirebaseAuth.mintToken(uid = uid)
    }

    private suspend fun HttpClient.provisionSelf(token: String): UserResponse =
        post(urlString = "/api/v1/users") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
            contentType(type = ContentType.Application.Json)
            setBody(body = CreateUserRequest(proposedRating = "4.0", displayName = "Player", dateOfBirth = "2000-01-01", sex = "Male"))
        }.body()

    @Test
    fun `an admin reads rating-change traces with resolved names, sets a comment, and non-admins are refused`() =
        withApp { client ->
            val adminToken = seedAdmin(uid = "admin")
            val player = client.provisionSelf(token = TestFirebaseAuth.mintToken(uid = "p1"))
            // Setting a rating records a RATING_SET audit entry (actor = admin, target = player).
            client.put(urlString = "/api/v1/users/${player.id}/ratings") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = SetRatingRequest(value = "4.0"))
            }

            val listed =
                client.get(urlString = "/api/v1/audit?category=RATING_CHANGE") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                }
            listed.status shouldBe HttpStatusCode.OK
            val body = listed.body<AuditLogResponse>()
            body.total shouldBe 1
            val entry = body.items.single()
            entry.action shouldBe "RATING_SET"
            entry.actor?.displayName shouldBe "admin"
            entry.target?.displayName shouldBe "Player"
            entry.target?.userId shouldBe player.id

            // Set an admin note on the entry.
            client.patch(urlString = "/api/v1/audit/${entry.id}/comment") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = AuditCommentRequest(comment = "Initial assessment"))
            }.status shouldBe HttpStatusCode.NoContent

            // A non-admin cannot read the audit log.
            client.get(urlString = "/api/v1/audit") {
                header(key = HttpHeaders.Authorization, value = "Bearer ${TestFirebaseAuth.mintToken(uid = "p1")}")
            }.status shouldBe HttpStatusCode.Forbidden

            // An unknown category is a 400.
            client.get(urlString = "/api/v1/audit?category=BOGUS") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
            }.status shouldBe HttpStatusCode.BadRequest
        }
}
