// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

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
import org.skopeo.dto.match.CreateFixtureRequest
import org.skopeo.dto.match.MatchResponse
import org.skopeo.dto.match.MatchResultRequest
import org.skopeo.dto.match.SetScoreRequest
import org.skopeo.dto.rating.CalculationRequest
import org.skopeo.dto.rating.CalculationResponse
import org.skopeo.dto.rating.SetRatingRequest
import org.skopeo.dto.rating.UserRatingResponse
import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.dto.user.UserResponse
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.module
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestFirebaseAuth

/**
 * End-to-end exercise of the rating calculation trigger: a dry-run previews without writing, an
 * explicit commit moves ratings, and non-admins are refused.
 */
class CalculationApiIntegrationTest {
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

    private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient { install(ContentNegotiation) { json() } }

    private fun withApp(block: suspend (HttpClient) -> Unit) =
        testApplication {
            application { module(initDatabase = false, firebaseAuth = TestFirebaseAuth.settings) }
            block(jsonClient())
        }

    private fun seedAdmin(uid: String = "admin"): String {
        UserRepository().provision(
            ProvisionUserCommand(
                firebaseUid = uid,
                identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                names = listOf(UserName(type = NameType.DISPLAY, value = uid)),
                capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR),
            ),
        )
        return TestFirebaseAuth.mintToken(uid = uid)
    }

    private suspend fun HttpClient.provisionSelf(token: String): UserResponse =
        post("/api/v1/users") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CreateUserRequest(displayName = "Player", dateOfBirth = "2000-01-01", sex = "Male"))
        }.body()

    private suspend fun HttpClient.rate(
        adminToken: String,
        userId: String,
    ) {
        put("/api/v1/users/$userId/ratings/NTRP") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(SetRatingRequest(value = "4.0"))
        }
    }

    private suspend fun HttpClient.ratingValue(
        token: String,
        userId: String,
    ): String =
        get("/api/v1/users/$userId/ratings") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<List<UserRatingResponse>>().single().value

    @Test
    fun `dry-run previews, commit moves ratings`() =
        withApp { client ->
            val admin = seedAdmin()
            val p1 = client.provisionSelf(TestFirebaseAuth.mintToken("p1"))
            val p2 = client.provisionSelf(TestFirebaseAuth.mintToken("p2"))
            client.rate(admin, p1.id)
            client.rate(admin, p2.id)
            val match =
                client.post("/api/v1/matches") {
                    header(HttpHeaders.Authorization, "Bearer $admin")
                    contentType(ContentType.Application.Json)
                    setBody(
                        CreateFixtureRequest(
                            ratingSystem = "NTRP",
                            matchType = "SINGLES",
                            matchFormat = "BEST_OF_THREE",
                            matchDate = "2026-01-01",
                            team1 = listOf(p1.id),
                            team2 = listOf(p2.id),
                        ),
                    )
                }.body<MatchResponse>()
            client.post("/api/v1/matches/${match.id}/result") {
                header(HttpHeaders.Authorization, "Bearer $admin")
                contentType(ContentType.Application.Json)
                setBody(MatchResultRequest(listOf(SetScoreRequest(6, 4), SetScoreRequest(6, 2))))
            }

            // Dry run (empty body → defaults to dryRun=true): previews, writes nothing.
            val dry =
                client.post("/api/v1/ratings/calculations") {
                    header(HttpHeaders.Authorization, "Bearer $admin")
                }
            dry.status shouldBe HttpStatusCode.OK
            dry.body<CalculationResponse>().let {
                it.dryRun shouldBe true
                it.matchesProcessed shouldBe 1
            }
            client.ratingValue(admin, p1.id) shouldBe "4.000000" // unchanged

            // Commit.
            val commit =
                client.post("/api/v1/ratings/calculations") {
                    header(HttpHeaders.Authorization, "Bearer $admin")
                    contentType(ContentType.Application.Json)
                    setBody(CalculationRequest(dryRun = false))
                }
            commit.body<CalculationResponse>().dryRun shouldBe false
            (client.ratingValue(admin, p1.id) != "4.000000") shouldBe true // moved
        }

    @Test
    fun `a non-admin cannot trigger the calculation`() =
        withApp { client ->
            seedAdmin()
            val p1 = client.provisionSelf(TestFirebaseAuth.mintToken("p1"))

            client.post("/api/v1/ratings/calculations") {
                header(HttpHeaders.Authorization, "Bearer ${TestFirebaseAuth.mintToken("p1")}")
                contentType(ContentType.Application.Json)
                setBody(CalculationRequest(dryRun = true))
            }.status shouldBe HttpStatusCode.Forbidden
            p1.id // referenced to avoid unused warning
        }
}
