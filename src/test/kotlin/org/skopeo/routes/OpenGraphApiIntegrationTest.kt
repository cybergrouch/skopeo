// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.withCharset
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.MatchType
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.TeamType
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.event.CreateEventInput
import org.skopeo.service.event.EventService
import org.skopeo.service.match.FixtureInput
import org.skopeo.service.match.MatchService
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Route-level tests for the Open Graph responder (#238). Uses the shared test DB so the services can
 * resolve entities, and an injected stub shell so no network fetch of index.html happens. Seeded
 * entities exercise the per-page cards; unknown codes fall back to the site-wide default card.
 */
class OpenGraphApiIntegrationTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }

        private const val STUB_SHELL =
            "<!doctype html><html lang=\"en\"><head><title>Skopeo</title></head>" +
                "<body><div id=\"root\"></div></body></html>"
    }

    private val users = UserRepository()
    private val ratings = RatingRepository()
    private val matches = MatchService(matches = MatchRepository(), ratings = ratings, users = users)
    private val events = EventService()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun provisionUser(
        uid: String,
        roles: Set<Capability> = setOf(element = Capability.PLAYER),
        rated: Boolean = false,
    ): User {
        val user =
            users.provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid,
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                        capabilities = roles,
                    ),
            )
        if (rated) {
            ratings.setRating(userId = user.id, rating = BigDecimal("4.0"), level = "4.0", confidence = BigDecimal("0.50"))
        }
        return user
    }

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private fun stubbedApp(block: suspend (io.ktor.client.HttpClient) -> Unit) =
        testApplication {
            application {
                configureOpenGraphRoutes(
                    shellProvider = WebShellProvider { STUB_SHELL },
                    origin = "https://skopeo.co",
                )
            }
            val client = createClient {}
            block(client)
        }

    @Test
    fun `a known match renders a versus card`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val ana = provisionUser(uid = "ana", rated = true)
        val bo = provisionUser(uid = "bo", rated = true)
        val fixture =
            matches
                .createFixture(
                    token = token(uid = "host"),
                    request =
                        FixtureInput(
                            matchFormat = TeamType.SINGLES,
                            matchType = MatchType.OPEN_PLAY,
                            matchDate = LocalDate.parse("2026-06-30"),
                            team1 = listOf(element = ana.id),
                            team2 = listOf(element = bo.id),
                        ),
                ).shouldBeRight()

        stubbedApp { client ->
            val body = client.get(urlString = "/matches/${fixture.publicCode}").bodyAsText()

            body shouldContain "<meta property=\"og:title\" content=\"ana vs bo on Skopeo\" />"
            body shouldContain "<div id=\"root\"></div>"
        }
    }

    @Test
    fun `a known player renders a profile card`() {
        val ana = provisionUser(uid = "ana", rated = true)

        stubbedApp { client ->
            val body = client.get(urlString = "/players/${ana.publicCode}").bodyAsText()

            body shouldContain "<meta property=\"og:title\" content=\"ana on Skopeo\" />"
            body shouldContain "<title>ana on Skopeo</title>"
        }
    }

    @Test
    fun `a known event renders an event card`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event =
            events
                .create(
                    token = token(uid = "host"),
                    input =
                        CreateEventInput(
                            name = "Club Open",
                            startDate = LocalDate.parse("2026-07-01"),
                            endDate = LocalDate.parse("2026-07-03"),
                            participantIds = emptyList(),
                        ),
                ).shouldBeRight()

        stubbedApp { client ->
            val body = client.get(urlString = "/events/${event.event.publicCode}").bodyAsText()

            body shouldContain "<meta property=\"og:title\" content=\"Club Open on Skopeo\" />"
        }
    }

    @Test
    fun `unknown match code serves the SPA shell with the default card`() =
        stubbedApp { client ->
            val response = client.get(urlString = "/matches/does-not-exist")

            response.status shouldBe HttpStatusCode.OK
            response.contentType() shouldBe ContentType.Text.Html.withCharset(charset = Charsets.UTF_8)
            val body = response.bodyAsText()
            body shouldContain "<div id=\"root\"></div>"
            body shouldContain "<meta property=\"og:type\" content=\"website\" />"
            body shouldContain "performance-based tennis ratings"
            body shouldContain "<meta property=\"og:url\" content=\"https://skopeo.co/matches/does-not-exist\" />"
        }

    @Test
    fun `unknown player code serves the default card`() =
        stubbedApp { client ->
            val body = client.get(urlString = "/players/nobody").bodyAsText()

            body shouldContain "<meta property=\"og:url\" content=\"https://skopeo.co/players/nobody\" />"
            body shouldContain "<meta name=\"twitter:card\" content=\"summary_large_image\" />"
        }

    @Test
    fun `unknown event code serves the default card`() =
        stubbedApp { client ->
            val body = client.get(urlString = "/events/ghost").bodyAsText()

            body shouldContain "<meta property=\"og:url\" content=\"https://skopeo.co/events/ghost\" />"
            body shouldContain "<meta property=\"og:image\" content=\"https://skopeo.co/og-cover.png\" />"
        }
}
