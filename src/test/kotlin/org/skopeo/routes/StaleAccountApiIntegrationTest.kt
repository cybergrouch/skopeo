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
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.dto.client.ApiClientResponse
import org.skopeo.common.dto.client.CreateApiClientRequest
import org.skopeo.common.dto.client.IssueApiKeyRequest
import org.skopeo.common.dto.client.IssuedApiKeyResponse
import org.skopeo.common.dto.rating.PendingAssessmentPageResponse
import org.skopeo.common.dto.user.StaleAccountSweepRequest
import org.skopeo.common.dto.user.StaleAccountSweepResponse
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.module
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestFirebaseAuth
import java.time.LocalDate

/**
 * The stale-account sweep over HTTP (#1122), including the exact request the Cloud Scheduler job sends:
 * POST with an `X-Api-Key` and `{"dryRun": false}`. As with the standings recompute, the route takes
 * `authenticate(FIREBASE_AUTH, optional = true)`, so it is worth proving end to end that "optional" did
 * not make it anonymous.
 */
class StaleAccountApiIntegrationTest {
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

    private fun seedUser(
        uid: String,
        roles: Set<Capability> = setOf(element = Capability.PLAYER),
        signedUpDaysAgo: Long = 0,
    ): User {
        val user =
            UserRepository().provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid,
                        identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                        sex = "Male",
                        capabilities = roles,
                    ),
            ).toDomain()
        transaction {
            exec(stmt = "UPDATE users SET created_at = now() - interval '$signedUpDaysAgo days' WHERE id = '${user.id}'")
        }
        return user
    }

    private fun adminAuth() = "Bearer ${TestFirebaseAuth.mintToken(uid = "admin")}"

    @Test
    fun `an ACCOUNT_SWEEPER key commits the sweep, the way the scheduler will`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val stale = seedUser(uid = "stale", signedUpDaysAgo = 45)
            val key = client.issueKeyFor(scopes = listOf(element = Capability.ACCOUNT_SWEEPER.name))

            val response =
                client.post(urlString = "/api/v1/users/stale-sweeps") {
                    header(key = "X-Api-Key", value = key.apiKey)
                    contentType(type = ContentType.Application.Json)
                    setBody(body = StaleAccountSweepRequest(dryRun = false))
                }

            response.status shouldBe HttpStatusCode.OK
            response.body<StaleAccountSweepResponse>().accounts.map { it.publicCode } shouldBe listOf(element = stale.publicCode)
            UserRepository().findById(id = stale.id).getOrNull()?.toDomain()?.isActive shouldBe false
        }

    @Test
    fun `an empty body is a dry run`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val stale = seedUser(uid = "stale", signedUpDaysAgo = 45)

            val body =
                client.post(urlString = "/api/v1/users/stale-sweeps") {
                    header(key = HttpHeaders.Authorization, value = adminAuth())
                }.body<StaleAccountSweepResponse>()

            body.dryRun shouldBe true
            body.accounts.map { it.publicCode } shouldBe listOf(element = stale.publicCode)
            UserRepository().findById(id = stale.id).getOrNull()?.toDomain()?.isActive shouldBe true
        }

    @Test
    fun `a key without the sweeper scope is forbidden, and no credential at all is unauthorized`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            // Least privilege (#597): an account manager's key restores accounts, it does not sweep them.
            val wrongScope = client.issueKeyFor(scopes = listOf(element = Capability.ACCOUNT_MANAGER.name))

            client.post(urlString = "/api/v1/users/stale-sweeps") {
                header(key = "X-Api-Key", value = wrongScope.apiKey)
            }.status shouldBe HttpStatusCode.Forbidden
            client.post(urlString = "/api/v1/users/stale-sweeps").status shouldBe HttpStatusCode.Unauthorized
        }

    @Test
    fun `the pending list shows the removal date, oldest sign-up first`() =
        withApp { client ->
            // Unrated too, so on the list as well — but staff, so never swept: no removal date.
            val admin = seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val newer = seedUser(uid = "newer", signedUpDaysAgo = 5)
            val older = seedUser(uid = "older", signedUpDaysAgo = 20)

            val page =
                client.get(urlString = "/api/v1/users/pending-assessment") {
                    header(key = HttpHeaders.Authorization, value = adminAuth())
                }.body<PendingAssessmentPageResponse>()

            page.items.map { it.publicCode } shouldBe listOf(older.publicCode, newer.publicCode, admin.publicCode)
            page.items.map { it.scheduledRemovalOn } shouldBe
                listOf(
                    LocalDate.now().minusDays(20).plusDays(30).toString(),
                    LocalDate.now().minusDays(5).plusDays(30).toString(),
                    null,
                )
        }

    private suspend fun HttpClient.issueKeyFor(scopes: List<String>): IssuedApiKeyResponse {
        val clientId =
            post(urlString = "/api/v1/api-clients") {
                header(key = HttpHeaders.Authorization, value = adminAuth())
                contentType(type = ContentType.Application.Json)
                setBody(body = CreateApiClientRequest(name = "Stale Account Sweeper"))
            }.body<ApiClientResponse>().id
        return post(urlString = "/api/v1/api-clients/$clientId/keys") {
            header(key = HttpHeaders.Authorization, value = adminAuth())
            contentType(type = ContentType.Application.Json)
            setBody(body = IssueApiKeyRequest(scopes = scopes))
        }.body<IssuedApiKeyResponse>()
    }
}
