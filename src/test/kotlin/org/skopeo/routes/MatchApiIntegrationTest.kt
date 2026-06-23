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
import io.ktor.client.request.put
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
import org.skopeo.dto.match.CreateFixtureRequest
import org.skopeo.dto.match.MatchResponse
import org.skopeo.dto.match.MatchResultRequest
import org.skopeo.dto.match.SetScoreRequest
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

/**
 * End-to-end exercise of the match API: a host creates a fixture between rated players, uploads
 * results (which completes but does not rate the match), and an admin sees it as pending
 * calculation; a non-staff user is refused.
 */
class MatchApiIntegrationTest {
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

    private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient { install(ContentNegotiation) { json() } }

    private fun withApp(block: suspend (HttpClient) -> Unit) =
        testApplication {
            application { module(initDatabase = false, firebaseAuth = TestFirebaseAuth.settings) }
            block(jsonClient())
        }

    private fun seedStaff(
        uid: String,
        roles: Set<Capability>,
    ): String {
        UserRepository().provision(
            ProvisionUserCommand(
                firebaseUid = uid,
                identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                names = listOf(UserName(type = NameType.DISPLAY, value = uid)),
                capabilities = roles + Capability.PLAYER,
            ),
        )
        return TestFirebaseAuth.mintToken(uid = uid)
    }

    private suspend fun HttpClient.provisionSelf(token: String): UserResponse =
        post("/api/v1/users") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CreateUserRequest(displayName = "Player"))
        }.body()

    private suspend fun HttpClient.rate(
        adminToken: String,
        userId: String,
    ) {
        put("/api/v1/users/$userId/ratings/NTRP") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(SetRatingRequest(value = "4.0"))
        }
    }

    private suspend fun HttpClient.createFixture(
        token: String,
        p1: String,
        p2: String,
    ): HttpResponse =
        post("/api/v1/matches") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                CreateFixtureRequest(
                    ratingSystem = "NTRP",
                    matchType = "SINGLES",
                    matchFormat = "BEST_OF_THREE",
                    matchDate = "2026-01-01",
                    team1 = listOf(p1),
                    team2 = listOf(p2),
                ),
            )
        }

    @Test
    fun `host creates a fixture, uploads results, admin sees it pending calculation`() =
        withApp { client ->
            val adminToken = seedStaff("admin", setOf(Capability.ADMINISTRATOR))
            val hostToken = seedStaff("host", setOf(Capability.HOST))
            val p1 = client.provisionSelf(TestFirebaseAuth.mintToken("p1"))
            val p2 = client.provisionSelf(TestFirebaseAuth.mintToken("p2"))
            client.rate(adminToken, p1.id)
            client.rate(adminToken, p2.id)

            val created = client.createFixture(hostToken, p1.id, p2.id)
            created.status shouldBe HttpStatusCode.Created
            val match = created.body<MatchResponse>()
            match.status shouldBe "SCHEDULED"

            val completed =
                client.post("/api/v1/matches/${match.id}/result") {
                    header(HttpHeaders.Authorization, "Bearer $hostToken")
                    contentType(ContentType.Application.Json)
                    setBody(MatchResultRequest(listOf(SetScoreRequest(6, 4), SetScoreRequest(6, 2))))
                }
            completed.status shouldBe HttpStatusCode.OK
            completed.body<MatchResponse>().let {
                it.status shouldBe "COMPLETED"
                it.ratedAt shouldBe null // not rated on upload
            }

            val pending =
                client.get("/api/v1/matches?filter=pending-calculation") {
                    header(HttpHeaders.Authorization, "Bearer $adminToken")
                }
            pending.status shouldBe HttpStatusCode.OK
            pending.body<List<MatchResponse>>().any { it.id == match.id } shouldBe true
        }

    @Test
    fun `oversight views are scoped - a host sees only their own fixtures, an admin sees all`() =
        withApp { client ->
            val adminToken = seedStaff("admin", setOf(Capability.ADMINISTRATOR))
            val host1 = seedStaff("host1", setOf(Capability.HOST))
            val host2 = seedStaff("host2", setOf(Capability.HOST))
            val players =
                (1..4).map { n ->
                    val p = client.provisionSelf(TestFirebaseAuth.mintToken("p$n"))
                    client.rate(adminToken, p.id)
                    p
                }

            val matchA = client.createFixture(host1, players[0].id, players[1].id).body<MatchResponse>()
            val matchB = client.createFixture(host2, players[2].id, players[3].id).body<MatchResponse>()

            suspend fun awaitingResults(token: String): List<String> =
                client
                    .get("/api/v1/matches?filter=awaiting-results") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.body<List<MatchResponse>>()
                    .map { it.id }

            awaitingResults(host1) shouldBe listOf(matchA.id)
            awaitingResults(host2) shouldBe listOf(matchB.id)
            awaitingResults(adminToken).toSet() shouldBe setOf(matchA.id, matchB.id)

            // Complete A → it moves to pending-calculation, still scoped to its host.
            client.post("/api/v1/matches/${matchA.id}/result") {
                header(HttpHeaders.Authorization, "Bearer $host1")
                contentType(ContentType.Application.Json)
                setBody(MatchResultRequest(listOf(SetScoreRequest(6, 4), SetScoreRequest(6, 2))))
            }

            suspend fun pendingCalculation(token: String): List<String> =
                client
                    .get("/api/v1/matches?filter=pending-calculation") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.body<List<MatchResponse>>()
                    .map { it.id }

            pendingCalculation(host1) shouldBe listOf(matchA.id)
            pendingCalculation(host2) shouldBe emptyList()
            pendingCalculation(adminToken) shouldBe listOf(matchA.id)
        }

    @Test
    fun `a non-staff user cannot list oversight views`() =
        withApp { client ->
            seedStaff("admin", setOf(Capability.ADMINISTRATOR))
            val player = TestFirebaseAuth.mintToken("p1")
            client.provisionSelf(player)

            client
                .get("/api/v1/matches?filter=awaiting-results") {
                    header(HttpHeaders.Authorization, "Bearer $player")
                }.status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `a non-staff user cannot create a fixture`() =
        withApp { client ->
            val adminToken = seedStaff("admin", setOf(Capability.ADMINISTRATOR))
            val p1 = client.provisionSelf(TestFirebaseAuth.mintToken("p1"))
            val p2 = client.provisionSelf(TestFirebaseAuth.mintToken("p2"))
            client.rate(adminToken, p1.id)
            client.rate(adminToken, p2.id)

            client.createFixture(TestFirebaseAuth.mintToken("p1"), p1.id, p2.id).status shouldBe HttpStatusCode.Forbidden
        }
}
