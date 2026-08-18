// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
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
import org.skopeo.common.dto.match.CreateFixtureRequest
import org.skopeo.common.dto.match.MatchPublicResponse
import org.skopeo.common.dto.match.MatchResponse
import org.skopeo.common.dto.match.MatchResultRequest
import org.skopeo.common.dto.match.MatchScoreCorrectionRequest
import org.skopeo.common.dto.match.MatchScoreCorrectionResponse
import org.skopeo.common.dto.match.SetScoreRequest
import org.skopeo.common.dto.rating.CalculationRequest
import org.skopeo.common.dto.rating.SetRatingRequest
import org.skopeo.common.dto.user.CreateUserRequest
import org.skopeo.common.dto.user.UserResponse
import org.skopeo.common.security.Capability
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.module
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestFirebaseAuth

/**
 * End-to-end score correction over HTTP (#776): a host records a result, an admin rates it, then the admin
 * corrects the score through `POST /api/v1/matches/{id}/score-correction` — dry-run first, then committed —
 * and the public match page starts reporting `reRated`. Also pins the ADMINISTRATOR-only gate at the route.
 */
class MatchScoreCorrectionApiIntegrationTest {
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

    private fun seedStaff(
        uid: String,
        roles: Set<Capability>,
    ): String {
        UserRepository().provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
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

    private suspend fun HttpClient.correct(
        token: String,
        matchId: String,
        team1Games: Int,
        team2Games: Int,
        dryRun: Boolean,
    ): HttpResponse =
        post(urlString = "/api/v1/matches/$matchId/score-correction") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
            contentType(type = ContentType.Application.Json)
            setBody(
                body =
                    MatchScoreCorrectionRequest(
                        sets = listOf(element = SetScoreRequest(team1Games = team1Games, team2Games = team2Games)),
                        dryRun = dryRun,
                    ),
            )
        }

    /** A rated match between two 4.0 players, ready to correct. Returns (adminToken, hostToken, match). */
    private suspend fun HttpClient.ratedMatch(): Triple<String, String, MatchResponse> {
        val adminToken = seedStaff(uid = "admin", roles = setOf(element = Capability.ADMINISTRATOR))
        val hostToken = seedStaff(uid = "host", roles = setOf(element = Capability.HOST))
        val p1 = provisionSelf(token = TestFirebaseAuth.mintToken(uid = "p1"))
        val p2 = provisionSelf(token = TestFirebaseAuth.mintToken(uid = "p2"))
        rate(adminToken = adminToken, userId = p1.id)
        rate(adminToken = adminToken, userId = p2.id)

        val match =
            post(urlString = "/api/v1/matches") {
                header(key = HttpHeaders.Authorization, value = "Bearer $hostToken")
                contentType(type = ContentType.Application.Json)
                setBody(
                    body =
                        CreateFixtureRequest(
                            matchFormat = "SINGLES",
                            matchType = "OPEN_PLAY",
                            matchDate = "2026-01-01",
                            team1 = listOf(element = p1.id),
                            team2 = listOf(element = p2.id),
                        ),
                )
            }.body<MatchResponse>()

        post(urlString = "/api/v1/matches/${match.id}/result") {
            header(key = HttpHeaders.Authorization, value = "Bearer $hostToken")
            contentType(type = ContentType.Application.Json)
            setBody(body = MatchResultRequest(sets = listOf(element = SetScoreRequest(team1Games = 6, team2Games = 4))))
        }
        // Commit the rating calculation so the match is RATED and therefore frozen to the normal edit path.
        post(urlString = "/api/v1/ratings/calculations") {
            header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
            contentType(type = ContentType.Application.Json)
            setBody(body = CalculationRequest(dryRun = false))
        }
        return Triple(first = adminToken, second = hostToken, third = match)
    }

    @Test
    fun `an admin previews then commits a score correction, and the public page reports it as re-rated (#776)`() =
        withApp { client ->
            val (adminToken, _, match) = client.ratedMatch()

            // The normal edit path stays frozen once rated — the correction endpoint is the only way in.
            val frozen =
                client.post(urlString = "/api/v1/matches/${match.id}/result") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = MatchResultRequest(sets = listOf(element = SetScoreRequest(team1Games = 6, team2Games = 0))))
                }
            frozen.status shouldBe HttpStatusCode.Conflict

            val preview = client.correct(token = adminToken, matchId = match.id, team1Games = 6, team2Games = 0, dryRun = true)
            preview.status shouldBe HttpStatusCode.OK
            preview.body<MatchScoreCorrectionResponse>().let {
                it.dryRun.shouldBeTrue()
                it.previousScore shouldBe "6-4"
                it.newScore shouldBe "6-0"
                it.impacts shouldHaveSize 2
            }
            // A dry run wrote nothing, so the public page still shows the original score and no badge.
            client
                .get(urlString = "/api/v1/matches/code/${match.publicCode}")
                .body<MatchPublicResponse>()
                .let {
                    it.reRated.shouldBeFalse()
                    it.sets.single().team2Games shouldBe 4
                }

            val committed = client.correct(token = adminToken, matchId = match.id, team1Games = 6, team2Games = 0, dryRun = false)
            committed.status shouldBe HttpStatusCode.OK
            committed.body<MatchScoreCorrectionResponse>().dryRun.shouldBeFalse()

            client
                .get(urlString = "/api/v1/matches/code/${match.publicCode}")
                .body<MatchPublicResponse>()
                .let {
                    // Still rated, now badged, and showing the corrected score.
                    it.rated.shouldBeTrue()
                    it.reRated.shouldBeTrue()
                    it.sets.single().team2Games shouldBe 0
                }
        }

    @Test
    fun `the public match page reveals the internal match id to an administrator only (#776)`() =
        withApp { client ->
            val (adminToken, hostToken, match) = client.ratedMatch()

            suspend fun idSeenBy(token: String?): String? =
                client
                    .get(urlString = "/api/v1/matches/code/${match.publicCode}") {
                        token?.let { header(key = HttpHeaders.Authorization, value = "Bearer $it") }
                    }.body<MatchPublicResponse>()
                    .id

            // Only an ADMINISTRATOR can act on the correction endpoint, so only they are handed the id.
            idSeenBy(token = adminToken) shouldBe match.id
            idSeenBy(token = hostToken).shouldBeNull()
            idSeenBy(token = null).shouldBeNull()
        }

    @Test
    fun `a host cannot correct a rated score even though they may record results (#776)`() =
        withApp { client ->
            val (_, hostToken, match) = client.ratedMatch()

            client
                .correct(token = hostToken, matchId = match.id, team1Games = 6, team2Games = 0, dryRun = false)
                .status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `an anonymous caller cannot reach the correction endpoint (#776)`() =
        withApp { client ->
            val (_, _, match) = client.ratedMatch()

            client
                .post(urlString = "/api/v1/matches/${match.id}/score-correction") {
                    contentType(type = ContentType.Application.Json)
                    setBody(
                        body =
                            MatchScoreCorrectionRequest(
                                sets = listOf(element = SetScoreRequest(team1Games = 6, team2Games = 0)),
                                dryRun = true,
                            ),
                    )
                }.status shouldBe HttpStatusCode.Unauthorized
        }
}
