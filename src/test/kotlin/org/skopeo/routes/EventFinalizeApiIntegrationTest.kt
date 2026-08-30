// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import org.skopeo.common.dto.event.CreateEventRequest
import org.skopeo.common.dto.event.EventResponse
import org.skopeo.common.dto.settings.SetAwardRankingPointsRequest
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.EventType
import org.skopeo.domain.model.MatchSetResult
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.event.CreateEventInput
import org.skopeo.domain.service.event.EventService
import org.skopeo.domain.service.rating.RatingAssembler
import org.skopeo.domain.service.rating.RatingCalculationService
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.module
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestFirebaseAuth
import org.skopeo.testsupport.seedFixtureClub
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** End-to-end exercise of the events finalize/un-finalize routes (#403, #477). */
class EventFinalizeApiIntegrationTest {
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
        ).toDomain()

    private fun tokenFor(uid: String): String = TestFirebaseAuth.mintToken(uid = uid, emailVerified = true)

    /**
     * POST an event as [token]. [ownerUid] must name the same account as [token]: every event needs a club
     * (#794) and `mayFileUnder` requires the creator to own it (#789). It is a separate parameter only
     * because the token here is an opaque string — and it stays a *single* uid so the refusal tests below
     * ("a non-owner host cannot un-finalize…") keep refusing for the right reason.
     */
    private suspend fun HttpClient.createEvent(
        token: String,
        ownerUid: String = "host",
        awardRankingPoints: Boolean? = null,
        participantIds: List<UUID> = emptyList(),
    ): EventResponse =
        post(urlString = "/api/v1/events") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
            contentType(type = ContentType.Application.Json)
            setBody(
                body =
                    CreateEventRequest(
                        clubId = seedFixtureClub(ownerUids = arrayOf(ownerUid)).id.toString(),
                        name = "Spring Open",
                        startDate = LocalDate.now().toString(),
                        endDate = LocalDate.now().plusDays(7).toString(),
                        format = "SINGLES",
                        participantIds = participantIds.map { it.toString() },
                        awardRankingPoints = awardRankingPoints,
                    ),
            )
        }.body()

    /** Flip the global "award ranking points" flag (#641) through the admin route, as a real client would. */
    private suspend fun HttpClient.setGlobalAwarding(
        adminToken: String,
        enabled: Boolean,
    ) {
        put(urlString = "/api/v1/settings/award-ranking-points") {
            header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
            contentType(type = ContentType.Application.Json)
            setBody(body = SetAwardRankingPointsRequest(enabled = enabled))
        }.status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `an admin finalizes then un-finalizes an event, ending open`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            val admin = tokenFor(uid = "admin")
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val event = client.createEvent(token = tokenFor(uid = "host"))

            client.post(urlString = "/api/v1/events/${event.id}/finalize") {
                header(key = HttpHeaders.Authorization, value = "Bearer $admin")
            }.let { it.status shouldBe HttpStatusCode.OK }

            val reopened =
                client.post(urlString = "/api/v1/events/${event.id}/unfinalize") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $admin")
                }
            reopened.status shouldBe HttpStatusCode.OK
            reopened.body<EventResponse>().isFinalized.shouldBeFalse()
        }

    @Test
    fun `a non-owner host cannot un-finalize another host's event, returning 403`() =
        withApp { client ->
            seedUser(uid = "owner", roles = setOf(Capability.PLAYER, Capability.HOST))
            seedUser(uid = "other", roles = setOf(Capability.PLAYER, Capability.HOST))
            val owner = tokenFor(uid = "owner")
            val event = client.createEvent(token = owner, ownerUid = "owner")
            client.post(urlString = "/api/v1/events/${event.id}/finalize") {
                header(key = HttpHeaders.Authorization, value = "Bearer $owner")
            }.let { it.status shouldBe HttpStatusCode.OK }

            client.post(urlString = "/api/v1/events/${event.id}/unfinalize") {
                header(key = HttpHeaders.Authorization, value = "Bearer ${tokenFor(uid = "other")}")
            }.let { it.status shouldBe HttpStatusCode.Forbidden }
        }

    @Test
    fun `un-finalizing a never-finalized event returns 400`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            val host = tokenFor(uid = "host")
            val event = client.createEvent(token = host)

            client.post(urlString = "/api/v1/events/${event.id}/unfinalize") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
            }.let { it.status shouldBe HttpStatusCode.BadRequest }
            // The event is still open after the rejected un-finalize.
            client.post(urlString = "/api/v1/events/${event.id}/finalize") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
            }.body<EventResponse>().isFinalized.shouldBeTrue()
        }

    // --- Global "award ranking points" flag enforced server-side (#752). ---

    @Test
    fun `creating an event with awardRankingPoints true is coerced to false while the global flag is off (#752)`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

            // A direct API call — no browser involved — opting in while the global flag is off.
            val event = client.createEvent(token = tokenFor(uid = "host"), awardRankingPoints = true)

            // Accepted, but the stored intent matches what was permitted: this event awards nothing.
            event.awardRankingPoints.shouldBeFalse()
        }

    @Test
    fun `creating an event with awardRankingPoints true keeps the opt-in while the global flag is on (#752)`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            client.setGlobalAwarding(adminToken = tokenFor(uid = "admin"), enabled = true)

            val event = client.createEvent(token = tokenFor(uid = "host"), awardRankingPoints = true)

            event.awardRankingPoints.shouldBeTrue()
        }

    @Test
    fun `finalizing an event created while the flag was on awards nothing once it is off, and says so (#752)`() =
        withApp { client ->
            val host = seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val p1 = seedUser(uid = "p1", roles = setOf(element = Capability.PLAYER))
            val p2 = seedUser(uid = "p2", roles = setOf(element = Capability.PLAYER))
            val admin = tokenFor(uid = "admin")
            client.setGlobalAwarding(adminToken = admin, enabled = true)
            // Rated players, so this fixture WOULD pay out (see the flag-on test) if nothing suppressed it.
            RatingAssembler().setRating(userId = p1.id, rating = BigDecimal("4.0"), level = "4.0")
            RatingAssembler().setRating(userId = p2.id, rating = BigDecimal("4.0"), level = "4.0")
            val event =
                client.createEvent(
                    token = tokenFor(uid = "host"),
                    awardRankingPoints = true,
                    participantIds = listOf(p1.id, p2.id),
                )
            event.awardRankingPoints.shouldBeTrue()
            seedCompletedOpenPlayFixture(eventId = UUID.fromString(event.id), host = host, p1 = p1, p2 = p2)
            // The kill switch is thrown AFTER the event was created with awarding on (issue #752, case 2).
            client.setGlobalAwarding(adminToken = admin, enabled = false)

            val response =
                client.post(urlString = "/api/v1/events/${event.id}/finalize") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $admin")
                }

            response.status shouldBe HttpStatusCode.OK
            val finalized = response.body<EventResponse>()
            finalized.isFinalized.shouldBeTrue()
            // The host is told the payout was suppressed rather than left to assume points were paid.
            finalized.awardingSuppressedByGlobalFlag.shouldBeTrue()
            RankingPointRepository().listActiveByEvent(eventId = UUID.fromString(event.id)).shouldBeEmpty()
        }

    @Test
    fun `finalizing with the flag left on still awards, and reports no suppression (#752)`() =
        withApp { client ->
            val host = seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val p1 = seedUser(uid = "p1", roles = setOf(element = Capability.PLAYER))
            val p2 = seedUser(uid = "p2", roles = setOf(element = Capability.PLAYER))
            val admin = tokenFor(uid = "admin")
            client.setGlobalAwarding(adminToken = admin, enabled = true)
            RatingAssembler().setRating(userId = p1.id, rating = BigDecimal("4.0"), level = "4.0")
            RatingAssembler().setRating(userId = p2.id, rating = BigDecimal("4.0"), level = "4.0")
            val event =
                client.createEvent(
                    token = tokenFor(uid = "host"),
                    awardRankingPoints = true,
                    participantIds = listOf(p1.id, p2.id),
                )
            seedCompletedOpenPlayFixture(eventId = UUID.fromString(event.id), host = host, p1 = p1, p2 = p2)

            val finalized =
                client.post(urlString = "/api/v1/events/${event.id}/finalize") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $admin")
                }.body<EventResponse>()

            finalized.awardingSuppressedByGlobalFlag.shouldBeFalse()
            // Open-play pays both sides (winner 3, loser 0) — the flag-on path is unchanged.
            RankingPointRepository().listActiveByEvent(eventId = UUID.fromString(event.id)) shouldHaveSize 2
        }

    /** Seed a COMPLETED open-play singles fixture on [eventId] won by [p1] — the thing a finalize pays out on. */
    private fun seedCompletedOpenPlayFixture(
        eventId: UUID,
        host: User,
        p1: User,
        p2: User,
    ) {
        val matchRepo = MatchRepository()
        val fixture =
            matchRepo.createFixture(
                command =
                    CreateFixtureCommand(
                        matchFormat = TeamType.SINGLES,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = LocalDate.now(),
                        team1UserIds = listOf(element = p1.id),
                        team2UserIds = listOf(element = p2.id),
                        team1Name = "t1",
                        team2Name = "t2",
                        createdBy = host.id,
                        eventId = eventId,
                    ),
            ).toDomain()
        matchRepo.addResult(
            matchId = fixture.id,
            sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 4, winnerTeamId = fixture.team1.teamId)),
            winnerTeamId = fixture.team1.teamId,
            recordedBy = host.id,
            completedAt = LocalDateTime.now(),
        )
    }

    // --- Reverse Ratings (#478). ---

    /**
     * Seed a rated, finalized, at-tip OPEN_PLAY event (host + admin already provisioned) directly via the
     * services against the same test DB, and return its id — the fixture the HTTP reverse-ratings call acts
     * on. Keeps the 200 test itself short (the deep behaviour is covered in EventReverseRatingsTest).
     */
    private fun seedRatedFinalizedEvent(
        host: User,
        p1: User,
        p2: User,
    ): String {
        val ratings = RatingAssembler()
        ratings.setRating(userId = p1.id, rating = BigDecimal("4.0"), level = "4.0")
        ratings.setRating(userId = p2.id, rating = BigDecimal("4.0"), level = "4.0")
        val matchRepo = MatchRepository()
        val eventService = EventService()
        val hostToken = VerifiedFirebaseToken(uid = "host", providerUid = "host".asRedactable())
        val eventId =
            eventService.create(
                token = hostToken,
                input =
                    CreateEventInput(
                        name = "Spring Open",
                        startDate = LocalDate.now(),
                        endDate = LocalDate.now().plusDays(7),
                        participantIds = listOf(p1.id, p2.id),
                        type = EventType.OPEN_PLAY.name,
                        // Every event needs a club (#794), owned by its creator (#789).
                        clubId = seedFixtureClub(ownerUids = arrayOf("host")).id,
                    ),
            ).let { UUID.fromString(requireNotNull(value = it.getOrNull()).id) }
        val fixture =
            matchRepo.createFixture(
                command =
                    CreateFixtureCommand(
                        matchFormat = TeamType.SINGLES,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = LocalDate.now(),
                        team1UserIds = listOf(element = p1.id),
                        team2UserIds = listOf(element = p2.id),
                        team1Name = "t1",
                        team2Name = "t2",
                        createdBy = host.id,
                        eventId = eventId,
                    ),
            ).toDomain()
        matchRepo.addResult(
            matchId = fixture.id,
            sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 4, winnerTeamId = fixture.team1.teamId)),
            winnerTeamId = fixture.team1.teamId,
            recordedBy = host.id,
            completedAt = LocalDateTime.now(),
        )
        eventService.finalize(token = hostToken, id = eventId).getOrNull().shouldNotBeNull()
        RatingCalculationService()
            .calculate(token = VerifiedFirebaseToken(uid = "admin", providerUid = "admin".asRedactable()), dryRun = false)
            .getOrNull().shouldNotBeNull()
        return eventId.toString()
    }

    @Test
    fun `reverse-ratings returns 200 for an admin on an at-tip rated event`() =
        withApp { client ->
            val host = seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val p1 = seedUser(uid = "p1", roles = setOf(element = Capability.PLAYER))
            val p2 = seedUser(uid = "p2", roles = setOf(element = Capability.PLAYER))
            val eventId = seedRatedFinalizedEvent(host = host, p1 = p1, p2 = p2)

            val response =
                client.post(urlString = "/api/v1/events/$eventId/reverse-ratings") {
                    header(key = HttpHeaders.Authorization, value = "Bearer ${tokenFor(uid = "admin")}")
                }
            response.status shouldBe HttpStatusCode.OK
            response.body<EventResponse>().isFinalized.shouldBeFalse()
        }

    @Test
    fun `reverse-ratings returns 403 for a non-admin host`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            val host = tokenFor(uid = "host")
            val event = client.createEvent(token = host)
            client.post(urlString = "/api/v1/events/${event.id}/finalize") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
            }.let { it.status shouldBe HttpStatusCode.OK }

            client.post(urlString = "/api/v1/events/${event.id}/reverse-ratings") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
            }.let { it.status shouldBe HttpStatusCode.Forbidden }
        }

    @Test
    fun `reverse-ratings returns 400 for a finalized event with no rated matches`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val host = tokenFor(uid = "host")
            val event = client.createEvent(token = host)
            client.post(urlString = "/api/v1/events/${event.id}/finalize") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
            }.let { it.status shouldBe HttpStatusCode.OK }

            client.post(urlString = "/api/v1/events/${event.id}/reverse-ratings") {
                header(key = HttpHeaders.Authorization, value = "Bearer ${tokenFor(uid = "admin")}")
            }.let { it.status shouldBe HttpStatusCode.BadRequest }
        }
}
