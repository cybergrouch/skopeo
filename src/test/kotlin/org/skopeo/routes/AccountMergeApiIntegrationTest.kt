// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
import org.skopeo.common.dto.user.MergeAccountsRequest
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

/** End-to-end exercise of the ADMINISTRATOR-only account-merge API (#643). */
class AccountMergeApiIntegrationTest {
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
    ): User =
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

    @Test
    fun `an admin merges the retired account into the survivor while a non-admin is forbidden`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val survivor = seedUser(uid = "survivor")
            val retired = seedUser(uid = "retired")
            val adminToken = TestFirebaseAuth.mintToken(uid = "admin")
            val retiredToken = TestFirebaseAuth.mintToken(uid = "retired")

            // A non-admin cannot merge.
            client.post(urlString = "/api/v1/users/${survivor.id}/merge") {
                header(key = HttpHeaders.Authorization, value = "Bearer $retiredToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = MergeAccountsRequest(retiredAccountId = retired.id.toString(), verificationNote = "same person"))
            }.status shouldBe HttpStatusCode.Forbidden

            // An admin merges successfully.
            client.post(urlString = "/api/v1/users/${survivor.id}/merge") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = MergeAccountsRequest(retiredAccountId = retired.id.toString(), verificationNote = "same person"))
            }.status shouldBe HttpStatusCode.OK

            // The retired account is retired as a "merged → survivor" card.
            val retiredAfter = UserRepository().findById(id = retired.id).getOrNull()!!.toDomain()
            retiredAfter.isActive shouldBe false
            retiredAfter.canonicalUserId shouldBe survivor.id
        }

    @Test
    fun `a blank verification note is rejected at the boundary`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val survivor = seedUser(uid = "survivor")
            val retired = seedUser(uid = "retired")
            val adminToken = TestFirebaseAuth.mintToken(uid = "admin")

            client.post(urlString = "/api/v1/users/${survivor.id}/merge") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = """{"retiredAccountId":"${retired.id}","verificationNote":"  "}""")
            }.status shouldBe HttpStatusCode.BadRequest
        }
}
