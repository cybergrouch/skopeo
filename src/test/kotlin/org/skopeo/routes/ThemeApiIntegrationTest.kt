// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
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
import org.skopeo.common.dto.settings.LocalThemeResponse
import org.skopeo.common.dto.settings.ScheduleDateDto
import org.skopeo.common.dto.settings.SetLocalThemeRequest
import org.skopeo.common.dto.settings.SetThemeRequest
import org.skopeo.common.dto.settings.SetThemeScheduleRequest
import org.skopeo.common.dto.settings.ThemeResponse
import org.skopeo.common.dto.settings.ThemeScheduleResponse
import org.skopeo.common.dto.settings.ThemeScheduleRowRequest
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

/** End-to-end exercise of the global UI theme API (#378): public read, admin-only write. */
class ThemeApiIntegrationTest {
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
        UserRepository()
            .provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid,
                        identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                        capabilities = roles,
                    ),
            ).toDomain()

    @Test
    fun `the theme is publicly readable and defaults to AUTO (#378)`() =
        withApp { client ->
            val anon = client.get(urlString = "/api/v1/theme")
            anon.status shouldBe HttpStatusCode.OK
            anon.body<ThemeResponse>().theme shouldBe "AUTO"
        }

    @Test
    fun `an admin sets the theme and the public read reflects it (#378)`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val adminToken = TestFirebaseAuth.mintToken(uid = "admin")

            val updated =
                client.put(urlString = "/api/v1/theme") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = SetThemeRequest(theme = "GRASS"))
                }
            updated.status shouldBe HttpStatusCode.OK
            updated.body<ThemeResponse>().theme shouldBe "GRASS"

            client.get(urlString = "/api/v1/theme").body<ThemeResponse>().theme shouldBe "GRASS"
        }

    @Test
    fun `a non-admin cannot set the theme (#378)`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            val hostToken = TestFirebaseAuth.mintToken(uid = "host")

            client
                .put(urlString = "/api/v1/theme") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $hostToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = SetThemeRequest(theme = "GRASS"))
                }.status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `setting an unknown theme is a 400 (#378)`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val adminToken = TestFirebaseAuth.mintToken(uid = "admin")

            client
                .put(urlString = "/api/v1/theme") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = SetThemeRequest(theme = "NEON"))
                }.status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `the local theme is unset by default (#514)`() =
        withApp { client ->
            seedUser(uid = "u", roles = setOf(element = Capability.PLAYER))
            val token = TestFirebaseAuth.mintToken(uid = "u")

            val response =
                client.get(urlString = "/api/v1/users/me/theme") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $token")
                }
            response.status shouldBe HttpStatusCode.OK
            response.body<LocalThemeResponse>().theme shouldBe null
        }

    @Test
    fun `the caller sets their local theme and the read reflects it (#514)`() =
        withApp { client ->
            seedUser(uid = "u", roles = setOf(element = Capability.PLAYER))
            val token = TestFirebaseAuth.mintToken(uid = "u")

            val set =
                client.put(urlString = "/api/v1/users/me/theme") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $token")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = SetLocalThemeRequest(theme = "GRASS"))
                }
            set.status shouldBe HttpStatusCode.OK
            val setBody = set.body<LocalThemeResponse>()
            setBody.theme shouldBe "GRASS"
            (setBody.setAt != null) shouldBe true

            client
                .get(urlString = "/api/v1/users/me/theme") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $token")
                }.body<LocalThemeResponse>()
                .theme shouldBe "GRASS"
        }

    @Test
    fun `PUT null clears the local theme (#514)`() =
        withApp { client ->
            seedUser(uid = "u", roles = setOf(element = Capability.PLAYER))
            val token = TestFirebaseAuth.mintToken(uid = "u")
            client.put(urlString = "/api/v1/users/me/theme") {
                header(key = HttpHeaders.Authorization, value = "Bearer $token")
                contentType(type = ContentType.Application.Json)
                setBody(body = SetLocalThemeRequest(theme = "CLAY"))
            }

            val cleared =
                client.put(urlString = "/api/v1/users/me/theme") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $token")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = SetLocalThemeRequest(theme = null))
                }
            cleared.status shouldBe HttpStatusCode.OK
            val body = cleared.body<LocalThemeResponse>()
            body.theme shouldBe null
            body.setAt shouldBe null
        }

    @Test
    fun `setting an unknown local theme is a 400 (#514)`() =
        withApp { client ->
            seedUser(uid = "u", roles = setOf(element = Capability.PLAYER))
            val token = TestFirebaseAuth.mintToken(uid = "u")

            client
                .put(urlString = "/api/v1/users/me/theme") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $token")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = SetLocalThemeRequest(theme = "NEON"))
                }.status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `an anonymous request cannot read the local theme (#514)`() =
        withApp { client ->
            client.get(urlString = "/api/v1/users/me/theme").status shouldBe HttpStatusCode.Unauthorized
        }

    private fun scheduleRow(
        name: String,
        start: Pair<Int, Int>,
        end: Pair<Int, Int>,
    ) = ThemeScheduleRowRequest(
        name = name,
        theme = "CLAY",
        start = ScheduleDateDto(month = start.first, day = start.second),
        end = ScheduleDateDto(month = end.first, day = end.second),
    )

    @Test
    fun `the public read carries the AUTO schedule (#1196)`() =
        withApp { client ->
            val body = client.get(urlString = "/api/v1/theme").body<ThemeResponse>()
            body.schedule.first().name shouldBe "Australian Open"
            body.schedule.size shouldBe 12
        }

    @Test
    fun `an admin saves the schedule and the public read reflects it (#1196)`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val adminToken = TestFirebaseAuth.mintToken(uid = "admin")

            val saved =
                client.put(urlString = "/api/v1/theme/schedule") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(
                        body =
                            SetThemeScheduleRequest(
                                schedule =
                                    listOf(
                                        element =
                                            scheduleRow(
                                                name = "Clay",
                                                start = 1 to 1,
                                                end =
                                                    12 to 31,
                                            ),
                                    ),
                            ),
                    )
                }
            saved.status shouldBe HttpStatusCode.OK
            saved.body<ThemeScheduleResponse>().gaps shouldBe emptyList()

            client
                .get(urlString = "/api/v1/theme")
                .body<ThemeResponse>()
                .schedule
                .map { it.name } shouldContainExactly listOf(element = "Clay")
        }

    @Test
    fun `an overlapping schedule is a 400 listing the conflicting rows (#1196)`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val adminToken = TestFirebaseAuth.mintToken(uid = "admin")

            val response =
                client.put(urlString = "/api/v1/theme/schedule") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(
                        body =
                            SetThemeScheduleRequest(
                                schedule =
                                    listOf(
                                        scheduleRow(name = "A", start = 1 to 1, end = 2 to 1),
                                        scheduleRow(
                                            name = "B",
                                            start =
                                                1 to 20,
                                            end = 3 to 1,
                                        ),
                                    ),
                            ),
                    )
                }
            response.status shouldBe HttpStatusCode.BadRequest
            val text = response.bodyAsText()
            text shouldContain "\"problems\""
            text shouldContain "\"code\":\"OVERLAP\""
            text shouldContain "\"rows\":[1,2]"
        }

    @Test
    fun `a non-admin cannot save the schedule (#1196)`() =
        withApp { client ->
            seedUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
            val hostToken = TestFirebaseAuth.mintToken(uid = "host")

            client
                .put(urlString = "/api/v1/theme/schedule") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $hostToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = SetThemeScheduleRequest(schedule = emptyList()))
                }.status shouldBe HttpStatusCode.Forbidden
        }
}
