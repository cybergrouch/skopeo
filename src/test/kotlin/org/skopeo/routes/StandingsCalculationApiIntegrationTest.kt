// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
import org.skopeo.common.dto.client.ApiClientResponse
import org.skopeo.common.dto.client.CreateApiClientRequest
import org.skopeo.common.dto.client.IssueApiKeyRequest
import org.skopeo.common.dto.client.IssuedApiKeyResponse
import org.skopeo.common.dto.standings.StandingsCalculationRequest
import org.skopeo.common.dto.standings.StandingsCalculationResponse
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.audit.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.AwardStatus
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.PointClass
import org.skopeo.domain.model.PointSourceType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.RankingPointAwardWrite
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.module
import org.skopeo.repository.AuditRepository
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
                    firebaseUid = uid.asRedactable(),
                    identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    sex = "Male",
                    capabilities = roles,
                ),
        ).toDomain()

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
                    // v1 is what a freshly migrated database seeds (#862).
                    pointsScheduleVersion = 1,
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
    // ---- The scheduled trigger's credential (#389) -----------------------------------------------
    //
    // These run the exact request shape Cloud Scheduler will send: POST with an X-Api-Key header and
    // {"dryRun": false}. The route takes `authenticate(FIREBASE_AUTH, optional = true)` so both
    // credentials reach the handler — worth exercising end to end, because "optional" is the kind of
    // change that can silently make an endpoint anonymous.

    @Test
    fun `a points-scoped API key can commit a recompute, the way the scheduler will (#389)`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val player = seedUser(uid = "player", roles = setOf(element = Capability.PLAYER))
            org.skopeo.repository.RatingRepository().setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")
            seedAward(userId = player.id)
            val adminAuth = "Bearer ${TestFirebaseAuth.mintToken(uid = "admin")}"
            val key = client.issueKeyFor(adminAuth = adminAuth, scopes = listOf(element = Capability.POINTS_MANAGER.name))

            val committed =
                client.post(urlString = "/api/v1/standings/calculations") {
                    header(key = "X-Api-Key", value = key.apiKey)
                    contentType(type = ContentType.Application.Json)
                    setBody(body = StandingsCalculationRequest(dryRun = false))
                }

            committed.status shouldBe HttpStatusCode.OK
            StandingsSnapshotRepository().latestPublishedPreferringPoints().shouldNotBeNull()
        }

    @Test
    fun `the run is attributed to the client, with no user (#389, #975)`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val adminAuth = "Bearer ${TestFirebaseAuth.mintToken(uid = "admin")}"
            val key = client.issueKeyFor(adminAuth = adminAuth, scopes = listOf(element = Capability.POINTS_MANAGER.name))

            client.post(urlString = "/api/v1/standings/calculations") {
                header(key = "X-Api-Key", value = key.apiKey)
            }.status shouldBe HttpStatusCode.OK

            // No invented service user: actorUserId stays null, and the client carries the attribution.
            // Without this the entry would be indistinguishable from the app's own background work.
            val entry =
                AuditRepository()
                    .list(actions = null, limit = 50, offset = 0)
                    .first
                    .map { it.toDomain() }
                    .first { it.action == AuditAction.STANDINGS_RECALCULATED }
            entry.actorUserId.shouldBeNull()
            entry.actorClientId.shouldNotBeNull()
        }

    @Test
    fun `a key without a points scope is forbidden, and no credential at all is unauthorized (#389)`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val adminAuth = "Bearer ${TestFirebaseAuth.mintToken(uid = "admin")}"

            // A valid key is not a licence: least privilege (#597) means the scope has to match.
            val wrongScope = client.issueKeyFor(adminAuth = adminAuth, scopes = listOf(element = Capability.RESEARCHER.name))
            client.post(urlString = "/api/v1/standings/calculations") {
                header(key = "X-Api-Key", value = wrongScope.apiKey)
            }.status shouldBe HttpStatusCode.Forbidden

            // And `optional = true` must not have made the endpoint anonymous.
            client.post(urlString = "/api/v1/standings/calculations").status shouldBe HttpStatusCode.Unauthorized
        }

    private suspend fun HttpClient.issueKeyFor(
        adminAuth: String,
        scopes: List<String>,
    ): IssuedApiKeyResponse {
        val clientId =
            post(urlString = "/api/v1/api-clients") {
                header(key = HttpHeaders.Authorization, value = adminAuth)
                contentType(type = ContentType.Application.Json)
                setBody(body = CreateApiClientRequest(name = "Standings Scheduler"))
            }.body<ApiClientResponse>().id
        return post(urlString = "/api/v1/api-clients/$clientId/keys") {
            header(key = HttpHeaders.Authorization, value = adminAuth)
            contentType(type = ContentType.Application.Json)
            setBody(body = IssueApiKeyRequest(scopes = scopes))
        }.body<IssuedApiKeyResponse>()
    }
}
