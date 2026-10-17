// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.contract.ReplayResponse
import org.skopeo.common.dto.livematch.LiveMatchResponse
import org.skopeo.common.dto.livematch.LiveScoreEventRequest
import org.skopeo.common.dto.match.MatchPublicResponse
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.livematch.LiveMatchEventKinds
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.module
import org.skopeo.repository.LiveMatchRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestFirebaseAuth
import org.skopeo.testsupport.fixtureEventId
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Who scored a live match, over the real HTTP and Firebase JWT path (#1183): the live scorer following a
 * handover, the finished match's public headline, and per-point scorers that reach only an entitled
 * caller and are omitted from the JSON for everyone else.
 *
 * Its own suite rather than more of `LiveMatchApiIntegrationTest`, which is at detekt's size limit.
 */
class ScorerAttributionApiIntegrationTest {
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

    private val users = UserRepository()

    private fun seedUser(
        uid: String,
        roles: Set<Capability> = setOf(element = Capability.PLAYER),
    ): UUID =
        users
            .provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid,
                        identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                        capabilities = roles,
                    ),
            ).toDomain()
            .id

    private fun codeOf(uid: String): String =
        users
            .findByFirebaseUid(firebaseUid = uid)
            .shouldNotBeNull()
            .toDomain()
            .publicCode

    /** A singles fixture under way and in a game, its setup rows recorded by [by]. */
    private fun fixtureInGame(by: UUID): UUID {
        val home = seedUser(uid = "home")
        val away = seedUser(uid = "away")
        val matchId =
            MatchRepository()
                .createFixture(
                    command =
                        CreateFixtureCommand(
                            matchFormat = TeamType.SINGLES,
                            matchType = MatchType.OPEN_PLAY,
                            matchDate = LocalDate.now(),
                            team1UserIds = listOf(element = home),
                            team2UserIds = listOf(element = away),
                            team1Name = "home",
                            team2Name = "away",
                            createdBy = home,
                            eventId = fixtureEventId(home, away),
                        ),
                ).toDomain()
                .id
        val live = LiveMatchRepository()
        listOf(
            LiveMatchEventKinds.MATCH_STARTED to null,
            LiveMatchEventKinds.SERVER_ASSIGNED to "TEAM1",
            LiveMatchEventKinds.SET_STARTED to null,
            LiveMatchEventKinds.GAME_STARTED to null,
        ).forEach { (kind, side) ->
            live.append(
                matchId = matchId,
                sequence = live.lastSequence(matchId = matchId) + 1,
                kind = kind,
                side = side,
                recordedBy = by,
                recordedAt = LocalDateTime.now(),
            )
        }
        return matchId
    }

    private suspend fun HttpClient.send(
        token: String,
        path: String,
        request: LiveScoreEventRequest? = null,
    ) = post(urlString = path) {
        header(key = HttpHeaders.Authorization, value = "Bearer $token")
        if (request != null) {
            contentType(type = ContentType.Application.Json)
            setBody(body = request)
        }
    }

    private suspend fun HttpClient.replay(
        code: String,
        token: String? = null,
    ) = get(urlString = "/api/v1/matches/code/$code/replay") {
        token?.let { header(key = HttpHeaders.Authorization, value = "Bearer $it") }
    }

    @Test
    fun `who scored follows a handover live, heads the finished match, and per-point scorers reach only the entitled`() =
        testApplication {
            application { module(initDatabase = false, firebaseAuth = TestFirebaseAuth.settings) }
            val client = createClient { install(plugin = ContentNegotiation) { json() } }
            val bossId = seedUser(uid = "boss", roles = setOf(Capability.PLAYER, Capability.SCORER, Capability.ADMINISTRATOR))
            val boss = TestFirebaseAuth.mintToken(uid = "boss")
            seedUser(uid = "relief", roles = setOf(Capability.PLAYER, Capability.SCORER))
            val relief = TestFirebaseAuth.mintToken(uid = "relief")
            seedUser(uid = "fan")
            val fan = TestFirebaseAuth.mintToken(uid = "fan")
            val matchId = fixtureInGame(by = bossId)
            val events = "/api/v1/matches/$matchId/live/events"

            // The relief umpire keys a game; the live view names whoever recorded the newest action.
            repeat(times = 4) {
                client.send(token = relief, path = events, request = LiveScoreEventRequest(kind = "POINT_WON", side = "TEAM1"))
            }
            val live: LiveMatchResponse =
                client
                    .get(urlString = "/api/v1/matches/$matchId/live") {
                        header(key = HttpHeaders.Authorization, value = "Bearer $relief")
                    }.body()
            live.scorerName shouldBe "relief"

            client.send(token = boss, path = events, request = LiveScoreEventRequest(kind = "SET_AWARDED", side = "TEAM1"))
            client.send(token = boss, path = "/api/v1/matches/$matchId/live/finalize").status shouldBe HttpStatusCode.OK
            val code =
                MatchRepository()
                    .findById(matchId = matchId)
                    .shouldBeRight()
                    .toDomain()
                    .publicCode

            // The headline is public and names the LAST scorer: boss awarded the set and the match.
            val scoredBy =
                client
                    .get(urlString = "/api/v1/matches/code/$code")
                    .body<MatchPublicResponse>()
                    .scoredBy
                    .shouldNotBeNull()
            scoredBy.displayName shouldBe "boss"
            scoredBy.publicCode shouldBe codeOf(uid = "boss")

            // Anonymous and a plain player: the same replay with every scorer omitted, not nulled.
            val anonymous = client.replay(code = code)
            anonymous.status shouldBe HttpStatusCode.OK
            anonymous.bodyAsText() shouldNotContain "scorer"
            client.replay(code = code, token = fan).bodyAsText() shouldNotContain "scorer"

            // An administrator gets each step's scorer code, resolved to a current name.
            val entitled = client.replay(code = code, token = boss).body<ReplayResponse>()
            val r = codeOf(uid = "relief")
            val b = codeOf(uid = "boss")
            entitled.version shouldBe 2
            entitled.points.map { it.scorer } shouldBe listOf(r, r, r, r, b, b)
            entitled.scorers.shouldNotBeNull().mapValues { it.value.displayName } shouldBe mapOf(r to "relief", b to "boss")
        }
}
