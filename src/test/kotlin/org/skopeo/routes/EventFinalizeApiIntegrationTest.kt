// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
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
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.module
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestFirebaseAuth
import java.time.LocalDate

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
}
