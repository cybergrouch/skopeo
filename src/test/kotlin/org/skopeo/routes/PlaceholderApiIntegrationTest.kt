// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import org.skopeo.dto.user.ClaimCodeResponse
import org.skopeo.dto.user.ClaimRequest
import org.skopeo.dto.user.CreatePlaceholderRequest
import org.skopeo.dto.user.UserResponse
import org.skopeo.dto.user.UserSummaryResponse
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.module
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestFirebaseAuth

/**
 * End-to-end exercise of the placeholder ("dummy") account routes (#496): create, list, generate a claim
 * code, and claim/adopt. Covers the authorized and unauthorized/error branch of each route lambda so the
 * handlers are no longer at 0.00 branch coverage.
 */
class PlaceholderApiIntegrationTest {
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
        roles: Set<Capability>,
    ): User =
        UserRepository().provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    sex = "Male",
                    capabilities = roles,
                ),
        )

    private fun tokenFor(uid: String): String = TestFirebaseAuth.mintToken(uid = uid, emailVerified = true)

    private suspend fun HttpClient.createPlaceholder(
        token: String,
        body: CreatePlaceholderRequest = CreatePlaceholderRequest(displayName = "Backlog Player", sex = "Male"),
    ): HttpResponse =
        post(urlString = "/api/v1/users/placeholders") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
            contentType(type = ContentType.Application.Json)
            setBody(body = body)
        }

    private suspend fun HttpClient.generateClaimCode(
        token: String,
        placeholderId: String,
    ): HttpResponse =
        post(urlString = "/api/v1/users/$placeholderId/claim-code") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
        }

    private suspend fun HttpClient.claim(
        token: String,
        code: String,
    ): HttpResponse =
        post(urlString = "/api/v1/users/claim") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
            contentType(type = ContentType.Application.Json)
            setBody(body = ClaimRequest(code = code))
        }

    // ---- createPlaceholder ----

    @Test
    fun `a host creates a placeholder, returning 201`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

            val response = client.createPlaceholder(token = tokenFor(uid = "host"))

            response.status shouldBe HttpStatusCode.Created
            val created = response.body<UserResponse>()
            created.publicCode.shouldNotBeEmpty()
        }

    @Test
    fun `an admin creates a placeholder, returning 201`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

            client.createPlaceholder(token = tokenFor(uid = "admin")).status shouldBe HttpStatusCode.Created
        }

    @Test
    fun `a plain player cannot create a placeholder, returning 403`() =
        withApp { client ->
            seedUser(uid = "plain", roles = setOf(element = Capability.PLAYER))

            client.createPlaceholder(token = tokenFor(uid = "plain")).status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `creating a placeholder with an invalid sex returns 400`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

            client
                .createPlaceholder(
                    token = tokenFor(uid = "host"),
                    body = CreatePlaceholderRequest(displayName = "Bad Sex", sex = "Other"),
                ).status shouldBe HttpStatusCode.BadRequest
        }

    // ---- listPlaceholders ----

    @Test
    fun `a host lists placeholders, returning 200`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            client.createPlaceholder(token = tokenFor(uid = "host")).status shouldBe HttpStatusCode.Created

            val response =
                client.get(urlString = "/api/v1/users/placeholders") {
                    header(key = HttpHeaders.Authorization, value = "Bearer ${tokenFor(uid = "host")}")
                }
            response.status shouldBe HttpStatusCode.OK
            response.body<List<UserSummaryResponse>>().isNotEmpty().shouldBeTrue()
        }

    @Test
    fun `a plain player cannot list placeholders, returning 403`() =
        withApp { client ->
            seedUser(uid = "plain", roles = setOf(element = Capability.PLAYER))

            client
                .get(urlString = "/api/v1/users/placeholders") {
                    header(key = HttpHeaders.Authorization, value = "Bearer ${tokenFor(uid = "plain")}")
                }.status shouldBe HttpStatusCode.Forbidden
        }

    // ---- generateClaimCode ----

    @Test
    fun `an admin generates a claim code, returning 201 with the plaintext once`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val placeholder = client.createPlaceholder(token = tokenFor(uid = "admin")).body<UserResponse>()

            val response = client.generateClaimCode(token = tokenFor(uid = "admin"), placeholderId = placeholder.id)

            response.status shouldBe HttpStatusCode.Created
            response.body<ClaimCodeResponse>().code.shouldNotBeEmpty()
        }

    @Test
    fun `a non-admin cannot generate a claim code, returning 403`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            val placeholder = client.createPlaceholder(token = tokenFor(uid = "admin")).body<UserResponse>()

            client
                .generateClaimCode(token = tokenFor(uid = "host"), placeholderId = placeholder.id)
                .status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `generating a claim code for a non-placeholder user returns 400`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val real = seedUser(uid = "real", roles = setOf(element = Capability.PLAYER))

            client
                .generateClaimCode(token = tokenFor(uid = "admin"), placeholderId = real.id.toString())
                .status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `generating a claim code for an unknown id returns 404`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

            client
                .generateClaimCode(token = tokenFor(uid = "admin"), placeholderId = "00000000-0000-0000-0000-000000000000")
                .status shouldBe HttpStatusCode.NotFound
        }

    @Test
    fun `generating a claim code for a malformed id returns 400`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

            client
                .generateClaimCode(token = tokenFor(uid = "admin"), placeholderId = "not-a-uuid")
                .status shouldBe HttpStatusCode.BadRequest
        }

    // ---- claim ----

    @Test
    fun `an empty account claims a placeholder, returning 200`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            seedUser(uid = "claimant", roles = setOf(element = Capability.PLAYER))
            val placeholder = client.createPlaceholder(token = tokenFor(uid = "admin")).body<UserResponse>()
            val code =
                client
                    .generateClaimCode(token = tokenFor(uid = "admin"), placeholderId = placeholder.id)
                    .body<ClaimCodeResponse>()
                    .code

            val response = client.claim(token = tokenFor(uid = "claimant"), code = code)

            response.status shouldBe HttpStatusCode.OK
            response.body<UserResponse>().id shouldBe seededClaimantId(uid = "claimant")
        }

    @Test
    fun `claiming with an unknown code returns 404`() =
        withApp { client ->
            seedUser(uid = "claimant", roles = setOf(element = Capability.PLAYER))

            client
                .claim(token = tokenFor(uid = "claimant"), code = "TOTALLYBOGUSCODE0000")
                .status shouldBe HttpStatusCode.NotFound
        }

    @Test
    fun `claiming with a blank code returns 400`() =
        withApp { client ->
            seedUser(uid = "claimant", roles = setOf(element = Capability.PLAYER))

            client.claim(token = tokenFor(uid = "claimant"), code = "   ").status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `a caller who has not signed up cannot claim, returning 403`() =
        withApp { client ->
            // No user provisioned for this uid → the caller is not signed up.
            client
                .claim(token = tokenFor(uid = "ghost"), code = "ANYCODE0000")
                .status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `re-claiming an already-consumed code returns 409`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            seedUser(uid = "first", roles = setOf(element = Capability.PLAYER))
            seedUser(uid = "second", roles = setOf(element = Capability.PLAYER))
            val placeholder = client.createPlaceholder(token = tokenFor(uid = "admin")).body<UserResponse>()
            val code =
                client
                    .generateClaimCode(token = tokenFor(uid = "admin"), placeholderId = placeholder.id)
                    .body<ClaimCodeResponse>()
                    .code
            client.claim(token = tokenFor(uid = "first"), code = code).status shouldBe HttpStatusCode.OK

            client.claim(token = tokenFor(uid = "second"), code = code).status shouldBe HttpStatusCode.NotFound
        }

    private fun seededClaimantId(uid: String): String = UserRepository().findByFirebaseUid(firebaseUid = uid)!!.id.toString()
}
