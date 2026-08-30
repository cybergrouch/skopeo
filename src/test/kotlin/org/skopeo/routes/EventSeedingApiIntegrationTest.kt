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
import org.skopeo.common.dto.event.EventResponse
import org.skopeo.common.dto.seeding.SaveSeedingOrderRequest
import org.skopeo.common.dto.seeding.SeedingResponse
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.rating.RatingAssembler
import org.skopeo.module
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestFirebaseAuth
import org.skopeo.testsupport.seedFixtureClub
import java.math.BigDecimal
import java.time.LocalDate

/** End-to-end exercise of the event-sourced seeding routes (#714): roster → generate → read back. */
class EventSeedingApiIntegrationTest {
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
     * POST an event as [token]. [ownerUid] must name the same account as [token]: every event needs a club
     * (#794) and `mayFileUnder` requires the creator to own it (#789). Kept to a *single* uid so the
     * "a non-owner host cannot seed another host's event" test below still refuses for the right reason.
     */
    private suspend fun HttpClient.createEvent(
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
                        name = "Spring Open",
                        startDate = LocalDate.now().toString(),
                        endDate = LocalDate.now().plusDays(7).toString(),
                        format = "SINGLES",
                    ),
            )
        }.body()

    @Test
    fun `a host adds a rated participant, generates the event seeding, and reads it back`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            val player = seedUser(uid = "p1", roles = setOf(element = Capability.PLAYER))
            RatingAssembler().setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")
            val host = tokenFor(uid = "host")
            val event = client.createEvent(token = host)

            client.post(urlString = "/api/v1/events/${event.id}/participants") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
                contentType(type = ContentType.Application.Json)
                setBody(body = AddParticipantRequest(userId = player.id.toString()))
            }.status shouldBe HttpStatusCode.OK

            val generated =
                client.post(urlString = "/api/v1/events/${event.id}/seeding") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $host")
                }
            generated.status shouldBe HttpStatusCode.OK
            generated.body<SeedingResponse>().entries.single().let {
                it.seed shouldBe 1
                it.position shouldBe 1
                it.userId shouldBe player.id.toString()
                it.ntrpBand shouldBe "4.0"
                // Raw rating is ADMINISTRATOR-only (#583): a non-admin HOST gets the band + seed, not the value.
                it.rating.shouldBeNull()
            }

            client.get(urlString = "/api/v1/events/${event.id}/seeding") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
            }.body<SeedingResponse>().entries.single().userId shouldBe player.id.toString()
        }

    @Test
    fun `a host saves a hand-reordered event seeding via PUT, renumbering seeds and flagging it (#718)`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            val p1 = seedUser(uid = "p1", roles = setOf(element = Capability.PLAYER))
            val p2 = seedUser(uid = "p2", roles = setOf(element = Capability.PLAYER))
            RatingAssembler().setRating(userId = p1.id, rating = BigDecimal("4.5"), level = "4.5")
            RatingAssembler().setRating(userId = p2.id, rating = BigDecimal("3.5"), level = "3.5")
            val host = tokenFor(uid = "host")
            val event = client.createEvent(token = host)
            for (id in listOf(p1.id, p2.id)) {
                client.post(urlString = "/api/v1/events/${event.id}/participants") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $host")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = AddParticipantRequest(userId = id.toString()))
                }.status shouldBe HttpStatusCode.OK
            }

            client.post(urlString = "/api/v1/events/${event.id}/seeding") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
            }.body<SeedingResponse>().manuallyEdited shouldBe false

            val saved =
                client.put(urlString = "/api/v1/events/${event.id}/seeding") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $host")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = SaveSeedingOrderRequest(userIds = listOf(p2.id.toString(), p1.id.toString())))
                }
            saved.status shouldBe HttpStatusCode.OK
            saved.body<SeedingResponse>().let {
                it.entries.map { e -> e.userId } shouldBe listOf(p2.id.toString(), p1.id.toString())
                it.entries.map { e -> e.seed } shouldBe listOf(1, 2)
                it.manuallyEdited shouldBe true
            }
        }

    @Test
    fun `a non-owner host cannot seed another host's event, returning 403`() =
        withApp { client ->
            seedUser(uid = "owner", roles = setOf(Capability.PLAYER, Capability.HOST))
            seedUser(uid = "other", roles = setOf(Capability.PLAYER, Capability.HOST))
            val event = client.createEvent(token = tokenFor(uid = "owner"), ownerUid = "owner")

            client.post(urlString = "/api/v1/events/${event.id}/seeding") {
                header(key = HttpHeaders.Authorization, value = "Bearer ${tokenFor(uid = "other")}")
            }.status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `reading an event seeding before one is generated returns 404`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            val host = tokenFor(uid = "host")
            val event = client.createEvent(token = host)

            client.get(urlString = "/api/v1/events/${event.id}/seeding") {
                header(key = HttpHeaders.Authorization, value = "Bearer $host")
            }.status shouldBe HttpStatusCode.NotFound
        }
}
