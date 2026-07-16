// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.collections.shouldHaveSize
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
import org.skopeo.dto.ranking.GrantRankingPointsRequest
import org.skopeo.dto.ranking.RankingPointAwardResponse
import org.skopeo.dto.ranking.RevokeRankingPointsRequest
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.module
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestFirebaseAuth
import java.math.BigDecimal

/** End-to-end exercise of the admin-only ranking-points API (#146). */
class RankingPointApiIntegrationTest {
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
                    sex = "Male",
                    capabilities = roles,
                ),
        )

    @Test
    fun `an admin grants, lists, and revokes an award while a non-admin is forbidden`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            seedUser(uid = "player-user", roles = setOf(element = Capability.PLAYER))
            val player = seedUser(uid = "player", roles = setOf(element = Capability.PLAYER))
            RatingRepository().setRating(userId = player.id, rating = BigDecimal("4.3"), level = "4.0")
            val adminToken = TestFirebaseAuth.mintToken(uid = "admin")
            val playerToken = TestFirebaseAuth.mintToken(uid = "player-user")

            // A non-admin cannot grant.
            client.post(urlString = "/api/v1/users/${player.id}/ranking-points") {
                header(key = HttpHeaders.Authorization, value = "Bearer $playerToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = GrantRankingPointsRequest(points = "100", pointClass = "ANNUAL_TOURNAMENT"))
            }.status shouldBe HttpStatusCode.Forbidden

            // The admin grants.
            val granted =
                client.post(urlString = "/api/v1/users/${player.id}/ranking-points") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = GrantRankingPointsRequest(points = "100", pointClass = "ANNUAL_TOURNAMENT"))
                }
            granted.status shouldBe HttpStatusCode.Created
            val award = granted.body<RankingPointAwardResponse>()
            award.band shouldBe "4.0"
            award.points shouldBe "100.0000"

            // The admin lists — one row so far.
            val list =
                client.get(urlString = "/api/v1/users/${player.id}/ranking-points") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                }
            list.status shouldBe HttpStatusCode.OK
            list.body<List<RankingPointAwardResponse>>() shouldHaveSize 1

            // The admin revokes it (204).
            client.post(urlString = "/api/v1/ranking-points/${award.id}/revoke") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = RevokeRankingPointsRequest(reason = "mistake"))
            }.status shouldBe HttpStatusCode.NoContent

            // Now the ledger has the original (revoked) + a marker row.
            client.get(urlString = "/api/v1/users/${player.id}/ranking-points") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
            }.body<List<RankingPointAwardResponse>>() shouldHaveSize 2
        }

    @Test
    fun `granting non-positive points is a 400`() =
        withApp { client ->
            val player = seedUser(uid = "player", roles = setOf(element = Capability.PLAYER))
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            RatingRepository().setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")
            val adminToken = TestFirebaseAuth.mintToken(uid = "admin")

            client.post(urlString = "/api/v1/users/${player.id}/ranking-points") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = GrantRankingPointsRequest(points = "0", pointClass = "ANNUAL_TOURNAMENT"))
            }.status shouldBe HttpStatusCode.BadRequest
        }
}
