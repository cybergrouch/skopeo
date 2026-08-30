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
import io.ktor.client.request.patch
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
import org.skopeo.common.dto.event.AddParticipantRequest
import org.skopeo.common.dto.event.CreateEventRequest
import org.skopeo.common.dto.event.CreateEventTeamRequest
import org.skopeo.common.dto.event.EventResponse
import org.skopeo.common.dto.event.EventTeamResponse
import org.skopeo.common.dto.event.UpdateEventTeamRequest
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
import org.skopeo.testsupport.TestFirebaseAuth
import org.skopeo.testsupport.seedFixtureClub
import java.time.LocalDate
import java.util.UUID

/** End-to-end exercise of the durable event-team routes (#720): create → list → update → dissolve. */
class EventTeamApiIntegrationTest {
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

    /**
     * POST a doubles event as [token]. [ownerUid] must name the same account as [token]: every event needs
     * a club (#794) and `mayFileUnder` requires the creator to own it (#789). Kept to a *single* uid so
     * "a non-owner host cannot create a team (403)" below still refuses for the right reason.
     */
    private suspend fun HttpClient.createDoublesEvent(
        token: String,
        ownerUid: String = "host",
    ): EventResponse =
        post(urlString = "/api/v1/events") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
            contentType(type = ContentType.Application.Json)
            setBody(
                body =
                    CreateEventRequest(
                        clubId = seedFixtureClub(ownerUids = arrayOf(ownerUid)).id.toString(),
                        name = "Doubles Cup",
                        startDate = LocalDate.now().toString(),
                        endDate = LocalDate.now().plusDays(7).toString(),
                        format = "DOUBLES",
                    ),
            )
        }.body()

    private suspend fun HttpClient.addParticipant(
        token: String,
        eventId: String,
        userId: String,
    ) {
        post(urlString = "/api/v1/events/$eventId/participants") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
            contentType(type = ContentType.Application.Json)
            setBody(body = AddParticipantRequest(userId = userId))
        }.status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `a host creates, lists, updates, and dissolves a durable team`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            val a = seedUser(uid = "Alice", roles = setOf(element = Capability.PLAYER))
            val b = seedUser(uid = "Bob", roles = setOf(element = Capability.PLAYER))
            val c = seedUser(uid = "Cara", roles = setOf(element = Capability.PLAYER))
            val host = tokenFor(uid = "host")
            val event = client.createDoublesEvent(token = host)
            listOf(a, b, c).forEach { client.addParticipant(token = host, eventId = event.id, userId = it.id.toString()) }

            val created =
                client.post(urlString = "/api/v1/events/${event.id}/teams") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $host")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = CreateEventTeamRequest(memberUserIds = listOf(a.id.toString(), b.id.toString()), name = null))
                }
            created.status shouldBe HttpStatusCode.Created
            val team = created.body<EventTeamResponse>()
            team.name shouldBe "Alice/Bob"
            team.members shouldHaveSize 2

            client.get(urlString = "/api/v1/events/${event.id}/teams") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
            }.body<List<EventTeamResponse>>() shouldHaveSize 1

            val updated =
                client.patch(urlString = "/api/v1/events/${event.id}/teams/${team.id}") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $host")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = UpdateEventTeamRequest(memberUserIds = listOf(a.id.toString(), c.id.toString()), name = "Renamed"))
                }
            updated.status shouldBe HttpStatusCode.OK
            updated.body<EventTeamResponse>().name shouldBe "Renamed"

            client.delete(urlString = "/api/v1/events/${event.id}/teams/${team.id}") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
            }.status shouldBe HttpStatusCode.NoContent

            client.get(urlString = "/api/v1/events/${event.id}/teams") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
            }.body<List<EventTeamResponse>>() shouldHaveSize 0
        }

    @Test
    fun `a one-member team is accepted on a doubles event, but a three-member team is rejected`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            val a = seedUser(uid = "Alice", roles = setOf(element = Capability.PLAYER))
            val b = seedUser(uid = "Bob", roles = setOf(element = Capability.PLAYER))
            val c = seedUser(uid = "Cara", roles = setOf(element = Capability.PLAYER))
            val host = tokenFor(uid = "host")
            val event = client.createDoublesEvent(token = host)
            listOf(a, b, c).forEach { client.addParticipant(token = host, eventId = event.id, userId = it.id.toString()) }

            // 2 is the hard cap, so a third member is a 400. Asserted before any team exists, so the
            // rejection can only be about size — not membership exclusivity.
            client.post(urlString = "/api/v1/events/${event.id}/teams") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
                contentType(type = ContentType.Application.Json)
                setBody(
                    body =
                        CreateEventTeamRequest(
                            memberUserIds = listOf(a.id.toString(), b.id.toString(), c.id.toString()),
                            name = null,
                        ),
                )
            }.status shouldBe HttpStatusCode.BadRequest

            // Team size is independent of the event format (#734): 1 member is allowed, if degenerate.
            val single =
                client.post(urlString = "/api/v1/events/${event.id}/teams") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $host")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = CreateEventTeamRequest(memberUserIds = listOf(element = a.id.toString()), name = null))
                }
            single.status shouldBe HttpStatusCode.Created
            single.body<EventTeamResponse>().members shouldHaveSize 1
        }

    @Test
    fun `a non-owner host cannot create a team (403)`() =
        withApp { client ->
            seedUser(uid = "owner", roles = setOf(Capability.PLAYER, Capability.HOST))
            seedUser(uid = "intruder", roles = setOf(Capability.PLAYER, Capability.HOST))
            val a = seedUser(uid = "Alice", roles = setOf(element = Capability.PLAYER))
            val b = seedUser(uid = "Bob", roles = setOf(element = Capability.PLAYER))
            val owner = tokenFor(uid = "owner")
            val event = client.createDoublesEvent(token = owner, ownerUid = "owner")
            listOf(a, b).forEach { client.addParticipant(token = owner, eventId = event.id, userId = it.id.toString()) }

            client.post(urlString = "/api/v1/events/${event.id}/teams") {
                header(key = HttpHeaders.Authorization, value = "Bearer ${tokenFor(uid = "intruder")}")
                contentType(type = ContentType.Application.Json)
                setBody(body = CreateEventTeamRequest(memberUserIds = listOf(a.id.toString(), b.id.toString()), name = null))
            }.status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `updating a non-existent team is a 404`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            val a = seedUser(uid = "Alice", roles = setOf(element = Capability.PLAYER))
            val b = seedUser(uid = "Bob", roles = setOf(element = Capability.PLAYER))
            val host = tokenFor(uid = "host")
            val event = client.createDoublesEvent(token = host)
            listOf(a, b).forEach { client.addParticipant(token = host, eventId = event.id, userId = it.id.toString()) }

            client.patch(urlString = "/api/v1/events/${event.id}/teams/${UUID.randomUUID()}") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
                contentType(type = ContentType.Application.Json)
                setBody(body = UpdateEventTeamRequest(memberUserIds = listOf(a.id.toString(), b.id.toString()), name = null))
            }.status shouldBe HttpStatusCode.NotFound
        }

    @Test
    fun `dissolving a non-existent team is a 404`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            val host = tokenFor(uid = "host")
            val event = client.createDoublesEvent(token = host)

            client.delete(urlString = "/api/v1/events/${event.id}/teams/${UUID.randomUUID()}") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
            }.status shouldBe HttpStatusCode.NotFound
        }
}
