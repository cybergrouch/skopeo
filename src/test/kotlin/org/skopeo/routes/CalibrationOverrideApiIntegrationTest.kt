// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import org.skopeo.common.dto.rating.PlayerCalibrationPageResponse
import org.skopeo.common.dto.rating.PlayerCalibrationResponse
import org.skopeo.common.dto.rating.SetCalibrationOverrideRequest
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.module
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestFirebaseAuth
import java.math.BigDecimal

/** The calibration override over HTTP (#1126): setting it, listing players in calibration, and the 403s. */
class CalibrationOverrideApiIntegrationTest {
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
    ): User =
        UserRepository()
            .provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid,
                        identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                        sex = "Male",
                        capabilities = roles,
                    ),
            ).toDomain()

    private fun auth(uid: String) = "Bearer ${TestFirebaseAuth.mintToken(uid = uid)}"

    @Test
    fun `a host overrides a player's calibration, and the player then appears as forced off`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            val player = seedUser(uid = "player")
            // A manual rating, which opens a calibration window.
            RatingRepository().setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")

            val listed =
                client.get(urlString = "/api/v1/ratings/calibrations") {
                    header(key = HttpHeaders.Authorization, value = auth(uid = "host"))
                }.body<PlayerCalibrationPageResponse>()
            listed.items.map { it.publicCode } shouldBe listOf(element = player.publicCode)

            val set =
                client.put(urlString = "/api/v1/users/${player.id}/calibration-override") {
                    header(key = HttpHeaders.Authorization, value = auth(uid = "host"))
                    contentType(type = ContentType.Application.Json)
                    setBody(body = SetCalibrationOverrideRequest(override = "FORCED_OFF", reason = "Known history"))
                }
            set.status shouldBe HttpStatusCode.OK
            set.body<PlayerCalibrationResponse>().inCalibration shouldBe false

            val forcedOff =
                client.get(urlString = "/api/v1/ratings/calibrations?includeForcedOff=true") {
                    header(key = HttpHeaders.Authorization, value = auth(uid = "host"))
                }.body<PlayerCalibrationPageResponse>()
            forcedOff.items.single().override shouldBe "FORCED_OFF"
        }

    @Test
    fun `a plain player can neither override nor list, and a blank reason is a 400`() =
        withApp { client ->
            seedUser(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
            seedUser(uid = "plain")
            val player = seedUser(uid = "player")
            RatingRepository().setRating(userId = player.id, rating = BigDecimal("4.0"), level = "4.0")

            client.get(urlString = "/api/v1/ratings/calibrations") {
                header(key = HttpHeaders.Authorization, value = auth(uid = "plain"))
            }.status shouldBe HttpStatusCode.Forbidden
            client.put(urlString = "/api/v1/users/${player.id}/calibration-override") {
                header(key = HttpHeaders.Authorization, value = auth(uid = "plain"))
                contentType(type = ContentType.Application.Json)
                setBody(body = SetCalibrationOverrideRequest(override = "FORCED_OFF", reason = "Because"))
            }.status shouldBe HttpStatusCode.Forbidden
            client.put(urlString = "/api/v1/users/${player.id}/calibration-override") {
                header(key = HttpHeaders.Authorization, value = auth(uid = "rater"))
                contentType(type = ContentType.Application.Json)
                setBody(body = SetCalibrationOverrideRequest(override = "FORCED_OFF", reason = "  "))
            }.status shouldBe HttpStatusCode.BadRequest
        }
}
