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
import org.skopeo.dto.invite.CreateInviteRequest
import org.skopeo.dto.invite.InvitePageResponse
import org.skopeo.dto.invite.InviteResponse
import org.skopeo.dto.user.CreateUserRequest
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

/** End-to-end exercise of admin invites and the invite-only provisioning gate (issue #74). */
class InviteApiIntegrationTest {
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

    private fun seedToken(
        uid: String,
        roles: Set<Capability>,
    ): String {
        UserRepository().provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = roles,
                ),
        )
        return TestFirebaseAuth.mintToken(uid = uid)
    }

    private suspend fun HttpClient.createInvite(
        token: String,
        email: String,
    ) = post(urlString = "/api/v1/invites") {
        header(key = HttpHeaders.Authorization, value = "Bearer $token")
        contentType(type = ContentType.Application.Json)
        setBody(body = CreateInviteRequest(email = email))
    }

    @Test
    fun `an admin can create, list, and revoke invites, and a non-admin cannot`() =
        withApp { client ->
            val adminToken = seedToken(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val playerToken = seedToken(uid = "player", roles = setOf(element = Capability.PLAYER))

            val created = client.createInvite(token = adminToken, email = "invitee@example.com")
            created.status shouldBe HttpStatusCode.Created
            val invite = created.body<InviteResponse>()
            invite.email shouldBe "invitee@example.com"
            invite.status shouldBe "PENDING"

            val list =
                client.get(urlString = "/api/v1/invites") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                }
            list.status shouldBe HttpStatusCode.OK
            list.body<InvitePageResponse>().items.any { it.email == "invitee@example.com" } shouldBe true

            // A non-admin is refused on every operation.
            client.createInvite(token = playerToken, email = "x@example.com").status shouldBe HttpStatusCode.Forbidden

            val revoke =
                client.delete(urlString = "/api/v1/invites/${invite.id}") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                }
            revoke.status shouldBe HttpStatusCode.NoContent
        }

    @Test
    fun `the route normalizes the email (trim + lowercase) before storing (#116)`() =
        withApp { client ->
            val adminToken = seedToken(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

            val created = client.createInvite(token = adminToken, email = "  New@Example.com ")
            created.status shouldBe HttpStatusCode.Created
            created.body<InviteResponse>().email shouldBe "new@example.com"
        }

    @Test
    fun `an invalid email is rejected at the route with a 400 (#116)`() =
        withApp { client ->
            val adminToken = seedToken(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

            client.createInvite(token = adminToken, email = "not-an-email").status shouldBe HttpStatusCode.BadRequest
            client.createInvite(token = adminToken, email = "   ").status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `listing accepts a status filter and rejects an unknown status`() =
        withApp { client ->
            val adminToken = seedToken(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            client.createInvite(token = adminToken, email = "pending@example.com")
            val toRevoke = client.createInvite(token = adminToken, email = "revoked@example.com").body<InviteResponse>()
            client.delete(urlString = "/api/v1/invites/${toRevoke.id}") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
            }

            val pending =
                client.get(urlString = "/api/v1/invites?status=PENDING") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                }
            pending.status shouldBe HttpStatusCode.OK
            pending.body<InvitePageResponse>().items.map { it.email } shouldBe listOf(element = "pending@example.com")

            val bad =
                client.get(urlString = "/api/v1/invites?status=BOGUS") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                }
            bad.status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `a manual sign-up is admitted only when its email has an open invite`() =
        withApp { client ->
            val adminToken = seedToken(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            client.createInvite(token = adminToken, email = "invited@example.com")

            // Password token whose email was invited → provisioning succeeds.
            val invitedToken = TestFirebaseAuth.mintToken(uid = "invited", email = "invited@example.com")
            provisionSelf(client = client, token = invitedToken).status shouldBe HttpStatusCode.Created

            // Password token without an invite → forbidden.
            val uninvitedToken = TestFirebaseAuth.mintToken(uid = "uninvited", email = "stranger@example.com")
            provisionSelf(client = client, token = uninvitedToken).status shouldBe HttpStatusCode.Forbidden
        }

    private suspend fun provisionSelf(
        client: HttpClient,
        token: String,
    ) = client.post(urlString = "/api/v1/users") {
        header(key = HttpHeaders.Authorization, value = "Bearer $token")
        contentType(type = ContentType.Application.Json)
        setBody(body = CreateUserRequest(displayName = "New Member", dateOfBirth = "2000-01-01", sex = "Male"))
    }
}
