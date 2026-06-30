// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.nulls.shouldBeNull
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
import org.skopeo.dto.match.MatchPublicResponse
import org.skopeo.dto.match.MatchResponse
import org.skopeo.dto.match.MatchResultRequest
import org.skopeo.dto.match.SetScoreRequest
import org.skopeo.dto.rating.CalculationRequest
import org.skopeo.dto.rating.MatchCalculationDetailResponse
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
        post(urlString = "/api/v1/users") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
            contentType(type = ContentType.Application.Json)
            setBody(body = CreateUserRequest(proposedRating = "4.0", displayName = "Player", dateOfBirth = "2000-01-01", sex = "Male"))
        }.body()

    private suspend fun HttpClient.rate(
        adminToken: String,
        userId: String,
    ) {
        put(urlString = "/api/v1/users/$userId/ratings") {
            header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
            contentType(type = ContentType.Application.Json)
            setBody(body = SetRatingRequest(value = "4.0"))
        }
    }

    private suspend fun HttpClient.createFixture(
        token: String,
        p1: String,
        p2: String,
    ): HttpResponse =
        post(urlString = "/api/v1/matches") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
            contentType(type = ContentType.Application.Json)
            setBody(
                body =
                    CreateFixtureRequest(
                        matchFormat = "SINGLES",
                        matchType = "OPEN_PLAY",
                        matchDate = "2026-01-01",
                        team1 = listOf(p1),
                        team2 = listOf(p2),
                    ),
            )
        }

    @Suppress("LongParameterList") // a faithful raw fixture body for exercising route-level shape validation
    private suspend fun HttpClient.postFixture(
        token: String,
        matchFormat: String = "SINGLES",
        matchType: String = "OPEN_PLAY",
        matchDate: String = "2026-01-01",
        team1: List<String>,
        team2: List<String>,
    ): HttpResponse =
        post(urlString = "/api/v1/matches") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
            contentType(type = ContentType.Application.Json)
            setBody(
                body =
                    CreateFixtureRequest(
                        matchFormat = matchFormat,
                        matchType = matchType,
                        matchDate = matchDate,
                        team1 = team1,
                        team2 = team2,
                    ),
            )
        }

    /** Post a raw JSON result body — bypasses the DTO init so we can exercise the wire-level 400. */
    private suspend fun HttpClient.postRawResult(
        token: String,
        matchId: String,
        json: String,
    ): HttpResponse =
        post(urlString = "/api/v1/matches/$matchId/result") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
            contentType(type = ContentType.Application.Json)
            setBody(body = json)
        }

    @Test
    fun `host creates a fixture, uploads results, admin sees it pending calculation`() =
        withApp { client ->
            val adminToken = seedStaff(uid = "admin", roles = setOf(Capability.ADMINISTRATOR))
            val hostToken = seedStaff(uid = "host", roles = setOf(Capability.HOST))
            val p1 = client.provisionSelf(token = TestFirebaseAuth.mintToken(uid = "p1"))
            val p2 = client.provisionSelf(token = TestFirebaseAuth.mintToken(uid = "p2"))
            client.rate(adminToken = adminToken, userId = p1.id)
            client.rate(adminToken = adminToken, userId = p2.id)

            val created = client.createFixture(token = hostToken, p1 = p1.id, p2 = p2.id)
            created.status shouldBe HttpStatusCode.Created
            val match = created.body<MatchResponse>()
            match.status shouldBe "SCHEDULED"
            match.matchFormat shouldBe "SINGLES"
            match.matchType shouldBe "OPEN_PLAY" // round-trips through persistence (#108)

            val completed =
                client.post(urlString = "/api/v1/matches/${match.id}/result") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $hostToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(
                        body =
                            MatchResultRequest(
                                sets =
                                    listOf(
                                        SetScoreRequest(team1Games = 6, team2Games = 4),
                                        SetScoreRequest(team1Games = 6, team2Games = 2),
                                    ),
                            ),
                    )
                }
            completed.status shouldBe HttpStatusCode.OK
            completed.body<MatchResponse>().let {
                it.status shouldBe "COMPLETED"
                it.ratedAt shouldBe null // not rated on upload
            }

            val pending =
                client.get(urlString = "/api/v1/matches?filter=pending-calculation") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                }
            pending.status shouldBe HttpStatusCode.OK
            pending.body<List<MatchResponse>>().any { it.id == match.id } shouldBe true
        }

    @Test
    fun `the public match page is reachable by code for any signed-in user (#136)`() =
        withApp { client ->
            val adminToken = seedStaff(uid = "admin", roles = setOf(Capability.ADMINISTRATOR))
            val hostToken = seedStaff(uid = "host", roles = setOf(Capability.HOST))
            val p1 = client.provisionSelf(token = TestFirebaseAuth.mintToken(uid = "p1"))
            val p2 = client.provisionSelf(token = TestFirebaseAuth.mintToken(uid = "p2"))
            client.rate(adminToken = adminToken, userId = p1.id)
            client.rate(adminToken = adminToken, userId = p2.id)
            val match = client.createFixture(token = hostToken, p1 = p1.id, p2 = p2.id).body<MatchResponse>()

            // A non-participant, non-staff signed-in user can still open the public page.
            val viewerToken = TestFirebaseAuth.mintToken(uid = "viewer")
            client.provisionSelf(token = viewerToken)
            val res =
                client.get(urlString = "/api/v1/matches/code/${match.publicCode}") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $viewerToken")
                }
            res.status shouldBe HttpStatusCode.OK
            res.body<MatchPublicResponse>().let {
                it.publicCode shouldBe match.publicCode
                it.team1.single().publicCode shouldBe p1.publicCode
                it.team2.single().publicCode shouldBe p2.publicCode
                it.winner shouldBe "NONE" // no result recorded yet
            }

            client
                .get(urlString = "/api/v1/matches/code/ZZZZZZ") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $viewerToken")
                }.status shouldBe HttpStatusCode.NotFound
        }

    @Test
    fun `oversight views are scoped - a host sees only their own fixtures, an admin sees all`() =
        withApp { client ->
            val adminToken = seedStaff(uid = "admin", roles = setOf(Capability.ADMINISTRATOR))
            val host1 = seedStaff(uid = "host1", roles = setOf(Capability.HOST))
            val host2 = seedStaff(uid = "host2", roles = setOf(Capability.HOST))
            val players =
                (1..4).map { n ->
                    val p = client.provisionSelf(token = TestFirebaseAuth.mintToken(uid = "p$n"))
                    client.rate(adminToken = adminToken, userId = p.id)
                    p
                }

            val matchA = client.createFixture(token = host1, p1 = players[0].id, p2 = players[1].id).body<MatchResponse>()
            val matchB = client.createFixture(token = host2, p1 = players[2].id, p2 = players[3].id).body<MatchResponse>()

            suspend fun awaitingResults(token: String): List<String> =
                client
                    .get(urlString = "/api/v1/matches?filter=awaiting-results") {
                        header(key = HttpHeaders.Authorization, value = "Bearer $token")
                    }.body<List<MatchResponse>>()
                    .map { it.id }

            awaitingResults(token = host1) shouldBe listOf(matchA.id)
            awaitingResults(token = host2) shouldBe listOf(matchB.id)
            awaitingResults(token = adminToken).toSet() shouldBe setOf(matchA.id, matchB.id)

            // Complete A → it moves to pending-calculation, still scoped to its host.
            client.post(urlString = "/api/v1/matches/${matchA.id}/result") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host1")
                contentType(type = ContentType.Application.Json)
                setBody(
                    body =
                        MatchResultRequest(
                            sets =
                                listOf(
                                    SetScoreRequest(team1Games = 6, team2Games = 4),
                                    SetScoreRequest(team1Games = 6, team2Games = 2),
                                ),
                        ),
                )
            }

            suspend fun pendingCalculation(token: String): List<String> =
                client
                    .get(urlString = "/api/v1/matches?filter=pending-calculation") {
                        header(key = HttpHeaders.Authorization, value = "Bearer $token")
                    }.body<List<MatchResponse>>()
                    .map { it.id }

            pendingCalculation(token = host1) shouldBe listOf(matchA.id)
            pendingCalculation(token = host2) shouldBe emptyList()
            pendingCalculation(token = adminToken) shouldBe listOf(matchA.id)
        }

    @Test
    fun `a participant can read a rated match's stored calculation detail`() =
        withApp { client ->
            val adminToken = seedStaff(uid = "admin", roles = setOf(Capability.ADMINISTRATOR))
            val p1 = client.provisionSelf(token = TestFirebaseAuth.mintToken(uid = "p1"))
            val p2 = client.provisionSelf(token = TestFirebaseAuth.mintToken(uid = "p2"))
            client.rate(adminToken = adminToken, userId = p1.id)
            client.rate(adminToken = adminToken, userId = p2.id)
            val match = client.createFixture(token = adminToken, p1 = p1.id, p2 = p2.id).body<MatchResponse>()
            client.post(urlString = "/api/v1/matches/${match.id}/result") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                contentType(type = ContentType.Application.Json)
                setBody(
                    body =
                        MatchResultRequest(
                            sets = listOf(SetScoreRequest(team1Games = 6, team2Games = 4), SetScoreRequest(team1Games = 6, team2Games = 2)),
                        ),
                )
            }
            // Commit the calculation so the breakdown is persisted.
            client.post(urlString = "/api/v1/ratings/calculations") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = CalculationRequest(dryRun = false))
            }

            val detail =
                client.get(urlString = "/api/v1/matches/${match.id}/calculation") {
                    header(key = HttpHeaders.Authorization, value = "Bearer ${TestFirebaseAuth.mintToken(uid = "p1")}")
                }
            detail.status shouldBe HttpStatusCode.OK
            detail.body<MatchCalculationDetailResponse>().let { body ->
                body.match.id shouldBe match.id
                body.changes.map { it.userId }.toSet() shouldBe setOf(p1.id, p2.id)
                val winner = body.changes.first { it.userId == p1.id }
                winner.displayName shouldBe "Player"
                // v2 (default) surfaces per-set steps; the net kFactor is null, each set carries its own (#110).
                winner.breakdown?.kFactor.shouldBeNull()
                winner.breakdown?.sets?.size shouldBe 2
                winner.breakdown?.sets?.first()?.kFactor shouldBe "0.160000"
            }
        }

    @Test
    fun `a non-staff user cannot list oversight views`() =
        withApp { client ->
            seedStaff(uid = "admin", roles = setOf(Capability.ADMINISTRATOR))
            val player = TestFirebaseAuth.mintToken(uid = "p1")
            client.provisionSelf(token = player)

            client
                .get(urlString = "/api/v1/matches?filter=awaiting-results") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $player")
                }.status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `a non-staff user cannot create a fixture`() =
        withApp { client ->
            val adminToken = seedStaff(uid = "admin", roles = setOf(Capability.ADMINISTRATOR))
            val p1 = client.provisionSelf(token = TestFirebaseAuth.mintToken(uid = "p1"))
            val p2 = client.provisionSelf(token = TestFirebaseAuth.mintToken(uid = "p2"))
            client.rate(adminToken = adminToken, userId = p1.id)
            client.rate(adminToken = adminToken, userId = p2.id)

            client
                .createFixture(token = TestFirebaseAuth.mintToken(uid = "p1"), p1 = p1.id, p2 = p2.id)
                .status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `fixture creation rejects bad request shape at the route with a 400 (#116)`() =
        withApp { client ->
            val adminToken = seedStaff(uid = "admin", roles = setOf(Capability.ADMINISTRATOR))
            val p1 = client.provisionSelf(token = TestFirebaseAuth.mintToken(uid = "p1"))
            val p2 = client.provisionSelf(token = TestFirebaseAuth.mintToken(uid = "p2"))
            val one = listOf(element = p1.id)
            val two = listOf(element = p2.id)

            // Invalid match format / type enums, a malformed date, and a malformed participant id.
            client.postFixture(token = adminToken, matchFormat = "TRIPLES", team1 = one, team2 = two).status shouldBe
                HttpStatusCode.BadRequest
            client.postFixture(token = adminToken, matchType = "FRIENDLY", team1 = one, team2 = two).status shouldBe
                HttpStatusCode.BadRequest
            client.postFixture(token = adminToken, matchDate = "01-01-2026", team1 = one, team2 = two).status shouldBe
                HttpStatusCode.BadRequest
            client.postFixture(token = adminToken, team1 = listOf(element = "not-a-uuid"), team2 = two).status shouldBe
                HttpStatusCode.BadRequest
            // Bad composition: singles needs exactly one per side, and a player can't appear twice.
            client.postFixture(token = adminToken, team1 = listOf(p1.id, p2.id), team2 = two).status shouldBe
                HttpStatusCode.BadRequest
            client.postFixture(token = adminToken, team1 = one, team2 = one).status shouldBe HttpStatusCode.BadRequest
            // A missing match type (required, no default) is rejected at deserialization.
            client.post(urlString = "/api/v1/matches") {
                header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = """{"matchFormat":"SINGLES","matchDate":"2026-01-01","team1":["${p1.id}"],"team2":["${p2.id}"]}""")
            }.status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `result upload rejects bad score shape at the route with a 400 (#116)`() =
        withApp { client ->
            val adminToken = seedStaff(uid = "admin", roles = setOf(Capability.ADMINISTRATOR))
            val matchId = java.util.UUID.randomUUID().toString()

            // Negative games and an empty set list are rejected at deserialization, before any match lookup.
            client.postRawResult(token = adminToken, matchId = matchId, json = """{"sets":[{"team1Games":-1,"team2Games":6}]}""")
                .status shouldBe HttpStatusCode.BadRequest
            client.postRawResult(token = adminToken, matchId = matchId, json = """{"sets":[]}""").status shouldBe
                HttpStatusCode.BadRequest
            // A set won on fewer than 4 games is rejected at the boundary too (#213).
            client.postRawResult(token = adminToken, matchId = matchId, json = """{"sets":[{"team1Games":3,"team2Games":2}]}""")
                .status shouldBe HttpStatusCode.BadRequest
        }
}
