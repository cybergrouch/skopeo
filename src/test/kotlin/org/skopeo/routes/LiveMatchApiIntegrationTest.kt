// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.dto.livematch.LiveMatchResponse
import org.skopeo.common.dto.livematch.LiveScoreEventRequest
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.livematch.LiveMatchEventKinds
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.MatchCompletionReason
import org.skopeo.domain.model.MatchStatus
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
 * The live-scoring API over the real Firebase JWT path (#911 step 3c).
 *
 * The service suite already covers the rules; this covers the things only the HTTP layer can get wrong —
 * status codes, the request parsing that had to live in the service because `routes` may not name a
 * `model` type, and that undo is a *server-targeted* endpoint rather than a postable kind.
 */
class LiveMatchApiIntegrationTest {
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
    ): UUID =
        UserRepository()
            .provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid.asRedactable(),
                        identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                        capabilities = roles,
                    ),
            ).toDomain()
            .id

    private fun seedScorer(uid: String = "ump"): String {
        seedUser(uid = uid, roles = setOf(Capability.PLAYER, Capability.SCORER))
        return TestFirebaseAuth.mintToken(uid = uid)
    }

    /**
     * An umpire who may also **finalize**.
     *
     * Scoring and finalizing are different rights. Keying points in is gated on `SCORING_ROLES`, flat
     * and club-agnostic. Finalizing goes through the existing `uploadResult`, which keeps the #789 club
     * rule — writing the permanent record is an organizer's act, and LiveMatch deliberately does not
     * weaken that gate to reach it. So a plain SCORER scores; an organizer finalizes.
     */
    private fun seedFinalizer(uid: String = "boss"): String {
        seedUser(uid = uid, roles = setOf(Capability.PLAYER, Capability.SCORER, Capability.ADMINISTRATOR))
        return TestFirebaseAuth.mintToken(uid = uid)
    }

    /**
     * A fixture that is **under way**: created, started, and with a server assigned.
     *
     * Since #984/#985/#986 the API refuses scoring on a match that has not been started or has nobody
     * serving. Seeded straight into the log rather than posted, so the arrangement does not depend on
     * the guards under test.
     */
    private fun seedFixture(): UUID {
        val matchId = seedScheduledFixture()
        val users = UserRepository()
        val server = users.findByFirebaseUid(firebaseUid = "home")!!.toDomain().id
        // Attribute the seeded rows to whichever umpire the test already created, not to a player.
        // `umpireCredit` folds "who recorded rows" into the match's umpires, so crediting `home` here
        // would invent a second umpire that the test never had.
        val by =
            listOf("ump", "boss")
                .firstNotNullOfOrNull { uid -> users.findByFirebaseUid(firebaseUid = uid)?.toDomain()?.id }
                ?: server
        val live = LiveMatchRepository()
        listOf(LiveMatchEventKinds.MATCH_STARTED to null, LiveMatchEventKinds.SERVER_ASSIGNED to server)
            .forEach { (kind, playerId) ->
                live.append(
                    matchId = matchId,
                    sequence = live.lastSequence(matchId = matchId) + 1,
                    kind = kind,
                    side = null,
                    playerId = playerId,
                    recordedBy = by,
                    recordedAt = LocalDateTime.now(),
                )
            }
        return matchId
    }

    /** A freshly created fixture, not yet started — the state an umpire first opens. */
    private fun seedScheduledFixture(): UUID {
        val home = seedUser(uid = "home")
        val away = seedUser(uid = "away")
        return MatchRepository()
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
    }

    private suspend fun HttpClient.postEvent(
        token: String,
        matchId: UUID,
        request: LiveScoreEventRequest,
    ) = post(urlString = "/api/v1/matches/$matchId/live/events") {
        header(key = HttpHeaders.Authorization, value = "Bearer $token")
        contentType(type = ContentType.Application.Json)
        setBody(body = request)
    }

    @Test
    fun `a scorer records points and reads the scoreboard back`() =
        withApp { client ->
            val token = seedScorer()
            val matchId = seedFixture()

            repeat(times = 3) {
                client.postEvent(
                    token = token,
                    matchId = matchId,
                    request = LiveScoreEventRequest(kind = "POINT_WON", side = "TEAM1"),
                ).status shouldBe HttpStatusCode.Created
            }

            val view: LiveMatchResponse =
                client.get(urlString = "/api/v1/matches/$matchId/live") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $token")
                }.body()

            view.pointsTeam1 shouldBe "40"
            view.pointsTeam2 shouldBe "0"
            // The two rows `seedFixture` seeds (start, server) plus the three points.
            view.sequence shouldBe 5L
        }

    @Test
    fun `a plain player is refused`() =
        withApp { client ->
            seedUser(uid = "nobody")
            val matchId = seedFixture()
            client
                .postEvent(
                    token = TestFirebaseAuth.mintToken(uid = "nobody"),
                    matchId = matchId,
                    request = LiveScoreEventRequest(kind = "POINT_WON", side = "TEAM1"),
                ).status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `an unknown kind is a 400 rather than a silent no-op`() =
        withApp { client ->
            val token = seedScorer()
            val matchId = seedFixture()
            client
                .postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "ACE"))
                .status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `UNDONE is not postable as a kind, because the server picks the target`() =
        withApp { client ->
            val token = seedScorer()
            val matchId = seedFixture()
            client
                .postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "UNDONE"))
                .status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `a kind that needs a side is refused without one`() =
        withApp { client ->
            val token = seedScorer()
            val matchId = seedFixture()
            client
                .postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "POINT_WON"))
                .status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `an unknown side is a 400`() =
        withApp { client ->
            val token = seedScorer()
            val matchId = seedFixture()
            client
                .postEvent(
                    token = token,
                    matchId = matchId,
                    request = LiveScoreEventRequest(kind = "POINT_WON", side = "LEFT"),
                ).status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `SERVER_ASSIGNED needs a player id, and rejects a malformed one`() =
        withApp { client ->
            val token = seedScorer()
            val matchId = seedFixture()
            val player = seedUser(uid = "server")

            client
                .postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "SERVER_ASSIGNED"))
                .status shouldBe HttpStatusCode.BadRequest
            client
                .postEvent(
                    token = token,
                    matchId = matchId,
                    request = LiveScoreEventRequest(kind = "SERVER_ASSIGNED", playerId = "not-a-uuid"),
                ).status shouldBe HttpStatusCode.BadRequest

            val ok =
                client.postEvent(
                    token = token,
                    matchId = matchId,
                    request = LiveScoreEventRequest(kind = "SERVER_ASSIGNED", playerId = player.toString()),
                )
            ok.status shouldBe HttpStatusCode.Created
            ok.body<LiveMatchResponse>().serverId shouldBe player.toString()
        }

    @Test
    fun `undo takes the last action back and returns 200`() =
        withApp { client ->
            val token = seedScorer()
            val matchId = seedFixture()
            client.postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "POINT_WON", side = "TEAM1"))
            client.postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "POINT_WON", side = "TEAM1"))

            val undone =
                client.post(urlString = "/api/v1/matches/$matchId/live/undo") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $token")
                }
            undone.status shouldBe HttpStatusCode.OK
            undone.body<LiveMatchResponse>().pointsTeam1 shouldBe "15"
        }

    @Test
    fun `undo with nothing to undo is 200, not an error`() =
        withApp { client ->
            val token = seedScorer()
            // Scheduled, deliberately: `seedFixture` seeds a start and a server, and those ARE undoable
            // actions — so on a match under way there is always something to take back.
            val matchId = seedScheduledFixture()
            val response =
                client.post(urlString = "/api/v1/matches/$matchId/live/undo") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $token")
                }
            response.status shouldBe HttpStatusCode.OK
            response.body<LiveMatchResponse>().sequence shouldBe 0L
        }

    @Test
    fun `claim and release round-trip`() =
        withApp { client ->
            val token = seedScorer()
            val matchId = seedFixture()

            val claimed =
                client.post(urlString = "/api/v1/matches/$matchId/live/claim") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $token")
                }
            claimed.status shouldBe HttpStatusCode.OK
            (claimed.body<LiveMatchResponse>().scorerId != null) shouldBe true

            val released =
                client.delete(urlString = "/api/v1/matches/$matchId/live/claim") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $token")
                }
            released.status shouldBe HttpStatusCode.OK
            released.body<LiveMatchResponse>().scorerId shouldBe null
        }

    @Test
    fun `a whole set is recorded through the API and banked with its tiebreak`() =
        withApp { client ->
            val token = seedScorer()
            val matchId = seedFixture()

            client.postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "MATCH_STARTED"))
            client.postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "TIEBREAK_STARTED"))
            repeat(times = 7) {
                client.postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "POINT_WON", side = "TEAM1"))
            }
            repeat(times = 5) {
                client.postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "POINT_WON", side = "TEAM2"))
            }
            val banked =
                client.postEvent(
                    token = token,
                    matchId = matchId,
                    request = LiveScoreEventRequest(kind = "SET_AWARDED", side = "TEAM1"),
                )

            val view = banked.body<LiveMatchResponse>()
            view.sets.shouldHaveSize(size = 1)
            view.sets.single().tiebreakTeam1Points shouldBe 7
            view.sets.single().tiebreakTeam2Points shouldBe 5
            view.sets.single().winner shouldBe "TEAM1"
            view.isTiebreak shouldBe false
            view.hasStarted shouldBe true
        }

    @Test
    fun `a retirement names the conceding side and hands the match over`() =
        withApp { client ->
            val token = seedScorer()
            val matchId = seedFixture()
            val retired =
                client.postEvent(
                    token = token,
                    matchId = matchId,
                    request = LiveScoreEventRequest(kind = "RETIRED", side = "TEAM2"),
                )

            val outcome = retired.body<LiveMatchResponse>().outcome
            outcome?.kind shouldBe "RETIRED"
            outcome?.winner shouldBe "TEAM1"
            outcome?.concededBy shouldBe "TEAM2"
        }

    private suspend fun HttpClient.finalize(
        token: String,
        matchId: UUID,
    ) = post(urlString = "/api/v1/matches/$matchId/live/finalize") {
        header(key = HttpHeaders.Authorization, value = "Bearer $token")
    }

    @Test
    fun `finalizing writes the live score into the match through uploadResult`() =
        withApp { client ->
            val token = seedFinalizer()
            val matchId = seedFixture()

            // 6-0, 6-0 played out, then declared.
            repeat(times = 2) {
                repeat(times = 6) { _ ->
                    repeat(times = 4) {
                        client.postEvent(
                            token = token,
                            matchId = matchId,
                            request = LiveScoreEventRequest(kind = "POINT_WON", side = "TEAM1"),
                        )
                    }
                }
                client.postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "SET_AWARDED", side = "TEAM1"))
            }
            client.postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "MATCH_AWARDED", side = "TEAM1"))

            val finalized = client.finalize(token = token, matchId = matchId)
            finalized.status shouldBe HttpStatusCode.OK
            // A MatchResponse, not a live view: after this the match IS the record.
            finalized.bodyAsText() shouldContain "COMPLETED"
        }

    @Test
    fun `a plain SCORER may score but may not finalize`() =
        withApp { client ->
            // The boundary between the two rights, asserted rather than discovered. Scoring is flat and
            // club-agnostic; finalizing writes the permanent record and keeps the #789 organizer gate.
            val token = seedScorer()
            val matchId = seedFixture()
            client
                .postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "MATCH_AWARDED", side = "TEAM1"))
                .status shouldBe HttpStatusCode.Created

            client.finalize(token = token, matchId = matchId).status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `finalizing before the match is declared over is refused`() =
        withApp { client ->
            val token = seedFinalizer()
            val matchId = seedFixture()
            client.postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "POINT_WON", side = "TEAM1"))

            client.finalize(token = token, matchId = matchId).status shouldBe HttpStatusCode.Conflict
        }

    @Test
    fun `a retirement mid-set finalizes with the partial set on the record`() =
        withApp { client ->
            val token = seedFinalizer()
            val matchId = seedFixture()

            // TEAM2 leads 3-1, then TEAM1 retires. Below the games floor, which #931 lifted for a
            // designated winner — this is the case that needed it.
            repeat(times = 3) {
                repeat(times = 4) {
                    client.postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "POINT_WON", side = "TEAM2"))
                }
            }
            repeat(times = 4) {
                client.postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "POINT_WON", side = "TEAM1"))
            }
            client.postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "RETIRED", side = "TEAM1"))

            val finalized = client.finalize(token = token, matchId = matchId)
            finalized.status shouldBe HttpStatusCode.OK
            finalized.bodyAsText() shouldContain "RETIRED"

            // The partial set is marked abandoned (#972), end to end. This is the only moment the fact
            // is knowable: once stored, 3-1 reads exactly like a set that was played out, and it clears
            // the games floor either way. Without the flag the points rule cannot tell the two apart.
            val stored = MatchRepository().findById(matchId = matchId).shouldBeRight().toDomain()
            stored.sets.map { it.abandoned } shouldBe listOf(element = true)
        }

    /** Four points to [side] — one game, so a set awarded afterwards is not a winnerless 0-0. */
    private suspend fun HttpClient.winAGame(
        token: String,
        matchId: UUID,
        side: String,
    ) {
        repeat(times = 4) {
            postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "POINT_WON", side = side))
        }
    }

    @Test
    fun `a match played to a finish is finalized from between sets (#984)`() =
        withApp { client ->
            // THE case this issue exists for, end to end. Before #984, awarding a set rolled straight
            // into the next, so a match that simply finished could never reach a finalizable state —
            // only a retirement or default could. The retirement path passing is what hid it.
            val token = seedFinalizer()
            val matchId = seedFixture()

            client.winAGame(token = token, matchId = matchId, side = "TEAM1")
            client.postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "SET_AWARDED", side = "TEAM1"))

            // Between sets: no declaration was recorded, and none is needed — finalizing here IS it.
            val finalized = client.finalize(token = token, matchId = matchId)
            finalized.status shouldBe HttpStatusCode.OK

            val stored = MatchRepository().findById(matchId = matchId).shouldBeRight().toDomain()
            stored.status shouldBe MatchStatus.COMPLETED
            stored.completionReason shouldBe MatchCompletionReason.COMPLETED
            // The winner comes from the banked sets, not from anything the client said.
            stored.winnerTeamId shouldBe stored.team1.teamId
        }

    @Test
    fun `finalizing with the sets level is refused rather than inventing a winner (#984)`() =
        withApp { client ->
            val token = seedFinalizer()
            val matchId = seedFixture()

            client.winAGame(token = token, matchId = matchId, side = "TEAM1")
            client.postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "SET_AWARDED", side = "TEAM1"))
            client.postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "SET_STARTED"))
            client.winAGame(token = token, matchId = matchId, side = "TEAM2")
            client.postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "SET_AWARDED", side = "TEAM2"))

            // One set all. Picking a winner to let finalize succeed would put a fabricated result into
            // the record — the umpire should play a decider or record a retirement.
            val refused = client.finalize(token = token, matchId = matchId)
            refused.status shouldBe HttpStatusCode.Conflict
            refused.bodyAsText() shouldContain "level"
        }

    @Test
    fun `scoring is refused between sets until the next one is started (#984)`() =
        withApp { client ->
            val token = seedFinalizer()
            val matchId = seedFixture()
            client.winAGame(token = token, matchId = matchId, side = "TEAM1")
            client.postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "SET_AWARDED", side = "TEAM1"))

            // A point here would silently join a set nobody has begun.
            client
                .postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "POINT_WON", side = "TEAM1"))
                .status shouldBe HttpStatusCode.Conflict

            client
                .postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "SET_STARTED"))
                .status shouldBe HttpStatusCode.Created
            client
                .postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "POINT_WON", side = "TEAM1"))
                .status shouldBe HttpStatusCode.Created
        }

    @Test
    fun `scoring is refused before the match is started (#986)`() =
        withApp { client ->
            val token = seedFinalizer()
            // Not seedFixture: this is about the state before anyone presses Start match.
            val matchId = seedScheduledFixture()

            client
                .postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "POINT_WON", side = "TEAM1"))
                .status shouldBe HttpStatusCode.Conflict

            client.postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "MATCH_STARTED"))
            // Still refused — started, but nobody is serving yet (#985).
            client
                .postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "POINT_WON", side = "TEAM1"))
                .status shouldBe HttpStatusCode.Conflict
        }

    @Test
    fun `a walkover finalizes with no sets at all`() =
        withApp { client ->
            // Nobody played. #911 needed MatchResultRequest to stop demanding a set when a winner is
            // designated, or this would have forced the caller to invent a 0-0 nobody played.
            val token = seedFinalizer()
            val matchId = seedFixture()
            client.postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "DEFAULTED", side = "TEAM2"))

            val finalized = client.finalize(token = token, matchId = matchId)
            finalized.status shouldBe HttpStatusCode.OK
            finalized.bodyAsText() shouldContain "DEFAULTED"
        }

    @Test
    fun `finalizing credits the umpire and leaves the log intact`() =
        withApp { client ->
            val token = seedFinalizer()
            val matchId = seedFixture()
            client.postEvent(token = token, matchId = matchId, request = LiveScoreEventRequest(kind = "MATCH_AWARDED", side = "TEAM1"))
            client.finalize(token = token, matchId = matchId).status shouldBe HttpStatusCode.OK

            // The credit is folded out because §8a makes the log disposable — but finalize itself does
            // NOT delete it, so a mis-finalized match can still be inspected.
            LiveMatchRepository().umpires(matchId = matchId).shouldHaveSize(size = 1)
            // The award, plus the start and server rows the fixture was seeded with.
            LiveMatchRepository().log(matchId = matchId).shouldHaveSize(size = 3)
        }

    @Test
    fun `an unknown match is a 404`() =
        withApp { client ->
            val token = seedScorer()
            client
                .postEvent(
                    token = token,
                    matchId = UUID.randomUUID(),
                    request = LiveScoreEventRequest(kind = "POINT_WON", side = "TEAM1"),
                ).status shouldBe HttpStatusCode.NotFound
        }

    @Test
    fun `the surface requires authentication`() =
        withApp { client ->
            val matchId = seedFixture()
            client.get(urlString = "/api/v1/matches/$matchId/live").status shouldBe HttpStatusCode.Unauthorized
        }
}
