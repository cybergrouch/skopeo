// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
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
import org.skopeo.common.dto.event.CreateEventRequest
import org.skopeo.common.dto.event.EventPublicResponse
import org.skopeo.common.dto.event.EventResponse
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.module
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestAppSettings
import org.skopeo.testsupport.TestFirebaseAuth
import org.skopeo.testsupport.seedFixtureClub
import java.time.LocalDate

/**
 * The read surface behind the unified event view (#741): `/events/{code}` is the single event page for
 * every audience, so the anonymous payload carries the lifecycle facts the page renders (format, class,
 * finalized state, ranking-points flag) and a staff viewer loads the organizer payload for the same
 * code. Self-signup is closed on an event that can no longer take joiners.
 */
class EventPublicViewApiIntegrationTest {
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
                    firebaseUid = uid.asRedactable(),
                    identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = roles,
                ),
        ).toDomain()

    private fun tokenFor(uid: String): String = TestFirebaseAuth.mintToken(uid = uid, emailVerified = true)

    private suspend fun HttpClient.createEvent(
        token: String,
        format: String = "SINGLES",
    ): EventResponse =
        post(urlString = "/api/v1/events") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
            contentType(type = ContentType.Application.Json)
            setBody(
                body =
                    CreateEventRequest(
                        // Every event needs a club (#794), owned by its creator (#789).
                        clubId = seedFixtureClub(ownerUids = arrayOf("host")).id.toString(),
                        name = "Spring Open",
                        startDate = LocalDate.now().toString(),
                        endDate = LocalDate.now().plusDays(7).toString(),
                        format = format,
                    ),
            )
        }.body()

    /** The anonymous read, with no Authorization header at all. */
    private suspend fun HttpClient.publicEvent(code: String): EventPublicResponse = get(urlString = "/api/v1/events/code/$code").body()

    @Test
    fun `the public event payload carries the format, class, finalized state, and ranking-points flag (#741)`() =
        withApp { client ->
            val hostUser = seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            // The global award flag (#641) is enforced at create (#752) and defaults off; turn it on so the
            // event keeps the awardRankingPoints opt-in this payload assertion is about.
            TestAppSettings.setAwardRankingPoints(enabled = true, updatedBy = hostUser.id)
            val host = tokenFor(uid = "host")
            val event = client.createEvent(token = host, format = "DOUBLES")

            client.publicEvent(code = event.publicCode).let {
                it.format shouldBe "DOUBLES"
                it.type shouldBe "OPEN_PLAY"
                it.isFinalized shouldBe false
                it.awardRankingPoints shouldBe true
            }

            client.post(urlString = "/api/v1/events/${event.id}/finalize") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
            }.status shouldBe HttpStatusCode.OK

            client.publicEvent(code = event.publicCode).isFinalized shouldBe true
        }

    @Test
    fun `self-signup is rejected once the event is finalized (#741)`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            seedUser(uid = "joiner", roles = setOf(element = Capability.PLAYER))
            val host = tokenFor(uid = "host")
            val event = client.createEvent(token = host)

            client.post(urlString = "/api/v1/events/${event.id}/finalize") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
            }.status shouldBe HttpStatusCode.OK

            client.post(urlString = "/api/v1/events/code/${event.publicCode}/signup") {
                header(key = HttpHeaders.Authorization, value = "Bearer ${tokenFor(uid = "joiner")}")
            }.status shouldBe HttpStatusCode.BadRequest

            client.publicEvent(code = event.publicCode).participants.shouldHaveSize(size = 0)
        }

    @Test
    fun `self-signup is rejected once the event is deleted (#741)`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            seedUser(uid = "joiner", roles = setOf(element = Capability.PLAYER))
            val host = tokenFor(uid = "host")
            val event = client.createEvent(token = host)

            client.delete(urlString = "/api/v1/events/${event.id}") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
            }.status shouldBe HttpStatusCode.NoContent

            client.post(urlString = "/api/v1/events/code/${event.publicCode}/signup") {
                header(key = HttpHeaders.Authorization, value = "Bearer ${tokenFor(uid = "joiner")}")
            }.status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `the manager view by code is staff-gated, and 404s on an unknown code (#741)`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            seedUser(uid = "player", roles = setOf(element = Capability.PLAYER))
            val host = tokenFor(uid = "host")
            val event = client.createEvent(token = host)

            // Staff get the organizer payload — including the id every mutation route is keyed by.
            val managed =
                client.get(urlString = "/api/v1/events/code/${event.publicCode}/manage") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $host")
                }
            managed.status shouldBe HttpStatusCode.OK
            managed.body<EventResponse>().id shouldBe event.id

            client.get(urlString = "/api/v1/events/code/${event.publicCode}/manage") {
                header(key = HttpHeaders.Authorization, value = "Bearer ${tokenFor(uid = "player")}")
            }.status shouldBe HttpStatusCode.Forbidden

            client.get(urlString = "/api/v1/events/code/NOPE1234/manage") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
            }.status shouldBe HttpStatusCode.NotFound
        }
}
