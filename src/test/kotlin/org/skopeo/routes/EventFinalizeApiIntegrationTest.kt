// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
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
import org.skopeo.dto.event.CreateEventRequest
import org.skopeo.dto.event.EventResponse
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.CreateFixtureCommand
import org.skopeo.model.EventType
import org.skopeo.model.MatchSetResult
import org.skopeo.model.MatchType
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.TeamType
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.module
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.event.CreateEventInput
import org.skopeo.service.event.EventService
import org.skopeo.service.rating.RatingCalculationService
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestFirebaseAuth
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

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
        )

    private fun tokenFor(uid: String): String = TestFirebaseAuth.mintToken(uid = uid, emailVerified = true)

    private suspend fun HttpClient.createEvent(token: String): EventResponse =
        post(urlString = "/api/v1/events") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
            contentType(type = ContentType.Application.Json)
            setBody(
                body =
                    CreateEventRequest(
                        name = "Spring Open",
                        startDate = LocalDate.now().toString(),
                        endDate = LocalDate.now().plusDays(7).toString(),
                    ),
            )
        }.body()

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
            val event = client.createEvent(token = owner)
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

    // --- Reverse Ratings (#478). ---

    /**
     * Seed a rated, finalized, at-tip LEAGUE event (host + admin already provisioned) directly via the
     * services against the same test DB, and return its id — the fixture the HTTP reverse-ratings call acts
     * on. Keeps the 200 test itself short (the deep behaviour is covered in EventReverseRatingsTest).
     */
    private fun seedRatedFinalizedEvent(
        host: User,
        p1: User,
        p2: User,
    ): String {
        val ratings = RatingRepository()
        ratings.setRating(userId = p1.id, rating = BigDecimal("4.0"), level = "4.0")
        ratings.setRating(userId = p2.id, rating = BigDecimal("4.0"), level = "4.0")
        val matchRepo = MatchRepository()
        val eventService = EventService()
        val hostToken = VerifiedFirebaseToken(uid = "host", providerUid = "host")
        val event =
            eventService.create(
                token = hostToken,
                input =
                    CreateEventInput(
                        name = "Spring Open",
                        startDate = LocalDate.now(),
                        endDate = LocalDate.now().plusDays(7),
                        participantIds = listOf(p1.id, p2.id),
                        type = EventType.LEAGUE,
                        minPointsPerMatch = 10,
                        maxPointsPerMatch = 50,
                        pointValidityStart = LocalDate.now(),
                        pointValidityEnd = LocalDate.now().plusDays(30),
                    ),
            ).let { requireNotNull(value = it.getOrNull()).event }
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
                        eventId = event.id,
                        designatedPoints = 30,
                    ),
            )
        matchRepo.addResult(
            matchId = fixture.id,
            sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 4, winnerTeamId = fixture.team1.teamId)),
            winnerTeamId = fixture.team1.teamId,
            recordedBy = host.id,
            completedAt = LocalDateTime.now(),
        )
        eventService.finalize(token = hostToken, id = event.id).getOrNull().shouldNotBeNull()
        RatingCalculationService()
            .calculate(token = VerifiedFirebaseToken(uid = "admin", providerUid = "admin"), dryRun = false)
            .getOrNull().shouldNotBeNull()
        return event.id.toString()
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
