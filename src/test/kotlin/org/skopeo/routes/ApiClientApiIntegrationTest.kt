// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
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
import org.skopeo.dto.client.ApiClientResponse
import org.skopeo.dto.client.ClientEffectiveCapabilitiesResponse
import org.skopeo.dto.client.ClientIdentityResponse
import org.skopeo.dto.client.CreateApiClientRequest
import org.skopeo.dto.client.IssueApiKeyRequest
import org.skopeo.dto.client.IssuedApiKeyResponse
import org.skopeo.dto.client.PublicPlayerResponse
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

/** End-to-end exercise of the partner API-clients admin API + the client-key `/client/me` resolver (#596). */
class ApiClientApiIntegrationTest {
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
                    identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = roles,
                ),
        )

    private fun bearer(uid: String) = "Bearer ${TestFirebaseAuth.mintToken(uid = uid)}"

    @Test
    fun `an admin creates a client, issues a key, lists it, and the key resolves at client-me`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val adminAuth = bearer(uid = "admin")

            val created =
                client.post(urlString = "/api/v1/api-clients") {
                    header(key = HttpHeaders.Authorization, value = adminAuth)
                    contentType(type = ContentType.Application.Json)
                    setBody(body = CreateApiClientRequest(name = "Partner A"))
                }
            created.status shouldBe HttpStatusCode.Created
            val clientId = created.body<ApiClientResponse>().id

            val issued =
                client.post(urlString = "/api/v1/api-clients/$clientId/keys") {
                    header(key = HttpHeaders.Authorization, value = adminAuth)
                    contentType(type = ContentType.Application.Json)
                    setBody(body = IssueApiKeyRequest(scopes = listOf(element = "PLAYER")))
                }
            issued.status shouldBe HttpStatusCode.Created
            val secret = issued.body<IssuedApiKeyResponse>()
            secret.apiKey shouldStartWith "skopeo_live_"

            val listed =
                client.get(urlString = "/api/v1/api-clients") {
                    header(key = HttpHeaders.Authorization, value = adminAuth)
                }
            listed.status shouldBe HttpStatusCode.OK
            listed.body<List<ApiClientResponse>>().single().keys shouldHaveSize 1

            // The issued plaintext resolves at the client-key-authenticated self-identity endpoint.
            val me =
                client.get(urlString = "/api/v1/client/me") {
                    header(key = "X-Api-Key", value = secret.apiKey)
                }
            me.status shouldBe HttpStatusCode.OK
            val identity = me.body<ClientIdentityResponse>()
            identity.clientId shouldBe clientId
            identity.scopes shouldBe listOf(element = "PLAYER")
        }

    @Test
    fun `a non-admin cannot manage clients, and an anonymous caller is unauthorized`() =
        withApp { client ->
            seedUser(uid = "plain", roles = setOf(element = Capability.PLAYER))
            client.post(urlString = "/api/v1/api-clients") {
                header(key = HttpHeaders.Authorization, value = bearer(uid = "plain"))
                contentType(type = ContentType.Application.Json)
                setBody(body = CreateApiClientRequest(name = "X"))
            }.status shouldBe HttpStatusCode.Forbidden

            // No Firebase token at all → the JWT provider rejects with 401.
            client.get(urlString = "/api/v1/api-clients").status shouldBe HttpStatusCode.Unauthorized
        }

    @Test
    fun `client-me rejects a missing or malformed key with 401`() =
        withApp { client ->
            client.get(urlString = "/api/v1/client/me").status shouldBe HttpStatusCode.Unauthorized
            client.get(urlString = "/api/v1/client/me") {
                header(key = "X-Api-Key", value = "garbage")
            }.status shouldBe HttpStatusCode.Unauthorized
        }

    private suspend fun HttpClient.issueKeyFor(
        adminAuth: String,
        scopes: List<String>,
    ): IssuedApiKeyResponse {
        val clientId =
            post(urlString = "/api/v1/api-clients") {
                header(key = HttpHeaders.Authorization, value = adminAuth)
                contentType(type = ContentType.Application.Json)
                setBody(body = CreateApiClientRequest(name = "Partner A"))
            }.body<ApiClientResponse>().id
        return post(urlString = "/api/v1/api-clients/$clientId/keys") {
            header(key = HttpHeaders.Authorization, value = adminAuth)
            contentType(type = ContentType.Application.Json)
            setBody(body = IssueApiKeyRequest(scopes = scopes))
        }.body<IssuedApiKeyResponse>()
    }

    @Test
    fun `the player directory is gated by the RESEARCHER scope (#597)`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            seedUser(uid = "alice", roles = setOf(element = Capability.PLAYER))
            val adminAuth = bearer(uid = "admin")

            // A key without the RESEARCHER scope is refused.
            val unscoped = client.issueKeyFor(adminAuth = adminAuth, scopes = emptyList())
            client.get(urlString = "/api/v1/client/players") {
                header(key = "X-Api-Key", value = unscoped.apiKey)
            }.status shouldBe HttpStatusCode.Forbidden

            // A key scoped for RESEARCHER may read the public directory.
            val scoped = client.issueKeyFor(adminAuth = adminAuth, scopes = listOf(element = "RESEARCHER"))
            val res =
                client.get(urlString = "/api/v1/client/players") {
                    header(key = "X-Api-Key", value = scoped.apiKey)
                }
            res.status shouldBe HttpStatusCode.OK
            res.body<List<PublicPlayerResponse>>().map { it.publicCode }.size shouldBe 2
        }

    @Test
    fun `me-capabilities returns the intersection and needs both a key and a user token (#597)`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            seedUser(uid = "researcher", roles = setOf(Capability.PLAYER, Capability.RESEARCHER))
            val adminAuth = bearer(uid = "admin")
            val key = client.issueKeyFor(adminAuth = adminAuth, scopes = listOf("RESEARCHER", "HOST"))

            // Delegated: key + the researcher's user token → intersection is just RESEARCHER.
            val ok =
                client.get(urlString = "/api/v1/client/me/capabilities") {
                    header(key = HttpHeaders.Authorization, value = bearer(uid = "researcher"))
                    header(key = "X-Api-Key", value = key.apiKey)
                }
            ok.status shouldBe HttpStatusCode.OK
            ok.body<ClientEffectiveCapabilitiesResponse>().capabilities shouldBe listOf(element = "RESEARCHER")

            // Missing the user token → the Firebase provider rejects with 401.
            client.get(urlString = "/api/v1/client/me/capabilities") {
                header(key = "X-Api-Key", value = key.apiKey)
            }.status shouldBe HttpStatusCode.Unauthorized

            // Missing the API key → the resolver rejects with 401.
            client.get(urlString = "/api/v1/client/me/capabilities") {
                header(key = HttpHeaders.Authorization, value = bearer(uid = "researcher"))
            }.status shouldBe HttpStatusCode.Unauthorized
        }

    @Test
    fun `a revoked key is forbidden at client-me`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val adminAuth = bearer(uid = "admin")
            val clientId =
                client.post(urlString = "/api/v1/api-clients") {
                    header(key = HttpHeaders.Authorization, value = adminAuth)
                    contentType(type = ContentType.Application.Json)
                    setBody(body = CreateApiClientRequest(name = "Partner A"))
                }.body<ApiClientResponse>().id
            val issued =
                client.post(urlString = "/api/v1/api-clients/$clientId/keys") {
                    header(key = HttpHeaders.Authorization, value = adminAuth)
                    contentType(type = ContentType.Application.Json)
                    setBody(body = IssueApiKeyRequest())
                }.body<IssuedApiKeyResponse>()

            // The key works before revocation…
            client.get(urlString = "/api/v1/client/me") {
                header(key = "X-Api-Key", value = issued.apiKey)
            }.status shouldBe HttpStatusCode.OK

            client.delete(urlString = "/api/v1/api-clients/$clientId/keys/${issued.key.id}") {
                header(key = HttpHeaders.Authorization, value = adminAuth)
            }.status shouldBe HttpStatusCode.NoContent

            // …and is forbidden after.
            client.get(urlString = "/api/v1/client/me") {
                header(key = "X-Api-Key", value = issued.apiKey)
            }.status shouldBe HttpStatusCode.Forbidden
        }
}
