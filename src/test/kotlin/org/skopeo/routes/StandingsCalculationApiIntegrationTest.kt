// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import org.skopeo.dto.standings.StandingsCalculationRequest
import org.skopeo.dto.standings.StandingsCalculationResponse
import org.skopeo.model.AuthProvider
import org.skopeo.model.AwardStatus
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.PointClass
import org.skopeo.model.PointSourceType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.RankingPointAwardWrite
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.module
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.StandingsSnapshotRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestFirebaseAuth
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/** End-to-end exercise of the admin-only points-based standings recompute API (#146 phase 2). */
class StandingsCalculationApiIntegrationTest {
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
        org.skopeo.repository.UserRepository().provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    sex = "Male",
                    capabilities = roles,
                ),
        )

    private fun seedAward(userId: UUID) {
        RankingPointRepository().award(
            write =
                RankingPointAwardWrite(
                    userId = userId,
                    points = BigDecimal("100"),
                    pointClass = PointClass.ANNUAL_TOURNAMENT,
                    sourceType = PointSourceType.INTERNAL,
                    sourceId = null,
                    band = "4.0",
                    sex = "Male",
                    reason = null,
                    validFrom = LocalDateTime.now().minusDays(1),
                    validUntil = LocalDateTime.now().plusMonths(6),
                    status = AwardStatus.ACTIVE,
                    revokesAwardId = null,
                    grantedBy = null,
                    awardedAt = LocalDateTime.now(),
                ),
        )
    }

    @Test
    fun `an admin dry-run previews with no persist and a commit persists while a non-admin is forbidden`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            seedUser(uid = "player-user", roles = setOf(element = Capability.PLAYER))
            val player = seedUser(uid = "player", roles = setOf(element = Capability.PLAYER))
            // Park the player in the award's band so band-scoped counting (#403 Phase D) counts it.
            org.skopeo.repository.RatingRepository().setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")
            seedAward(userId = player.id)
            val adminToken = TestFirebaseAuth.mintToken(uid = "admin")
            val playerToken = TestFirebaseAuth.mintToken(uid = "player-user")
            val snapshots = StandingsSnapshotRepository()

            // A non-admin is forbidden.
            client.post(urlString = "/api/v1/standings/calculations") {
                header(key = HttpHeaders.Authorization, value = "Bearer $playerToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = StandingsCalculationRequest(dryRun = true))
            }.status shouldBe HttpStatusCode.Forbidden

            // An admin dry-run (empty body defaults to a dry run) previews and persists nothing.
            val preview =
                client.post(urlString = "/api/v1/standings/calculations") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                }
            preview.status shouldBe HttpStatusCode.OK
            preview.body<StandingsCalculationResponse>().dryRun shouldBe true
            snapshots.latestPublishedPreferringPoints() shouldBe null

            // An explicit commit publishes a POINTS snapshot.
            val committed =
                client.post(urlString = "/api/v1/standings/calculations") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = StandingsCalculationRequest(dryRun = false))
                }
            committed.status shouldBe HttpStatusCode.OK
            val body = committed.body<StandingsCalculationResponse>()
            body.dryRun shouldBe false
            body.groupsComputed shouldBe 1
            (snapshots.latestPublishedPreferringPoints() != null) shouldBe true
        }
}
