// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.collections.shouldHaveSize
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
import org.skopeo.dto.points.ClubBudgetResponse
import org.skopeo.dto.points.ClubPointsSummaryResponse
import org.skopeo.dto.points.PointsPolicyResponse
import org.skopeo.dto.points.SetClubBudgetRequest
import org.skopeo.dto.points.SetPointsPolicyRequest
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.CreateClubCommand
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.module
import org.skopeo.repository.ClubRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestFirebaseAuth
import java.util.UUID

/** End-to-end exercise of the points-budget API (#403 Phase B). */
class PointsBudgetApiIntegrationTest {
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

    private fun seedClub(ownerId: UUID): UUID = ClubRepository().create(command = CreateClubCommand(name = "Club", createdBy = ownerId)).id

    @Test
    fun `a points manager reads and sets policies and budgets (#403)`() =
        withApp { client ->
            val pm = seedUser(uid = "pm", roles = setOf(Capability.PLAYER, Capability.POINTS_MANAGER))
            val pmToken = TestFirebaseAuth.mintToken(uid = "pm")
            val club = seedClub(ownerId = pm.id)

            // Read the seeded policies.
            val policiesGet =
                client.get(urlString = "/api/v1/points/policies") { header(key = HttpHeaders.Authorization, value = "Bearer $pmToken") }
            policiesGet.status shouldBe HttpStatusCode.OK
            policiesGet.body<List<PointsPolicyResponse>>() shouldHaveSize 3

            // Set the LEAGUE policy.
            val setPolicy =
                client.put(urlString = "/api/v1/points/policies/LEAGUE") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $pmToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = SetPointsPolicyRequest(minPoints = 4, maxPoints = 42, maxValidityDays = 90))
                }
            setPolicy.status shouldBe HttpStatusCode.OK
            setPolicy.body<PointsPolicyResponse>().maxPoints shouldBe 42

            // Set a club budget.
            val setBudget =
                client.put(urlString = "/api/v1/clubs/$club/point-budgets/LEAGUE") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $pmToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = SetClubBudgetRequest(budgetedPoints = 300))
                }
            setBudget.status shouldBe HttpStatusCode.OK
            setBudget.body<ClubBudgetResponse>().let {
                it.budgeted shouldBe 300
                it.allocated shouldBe 0
                it.free shouldBe 300
            }

            // Read all budgets.
            val budgets =
                client.get(urlString = "/api/v1/points/budgets") { header(key = HttpHeaders.Authorization, value = "Bearer $pmToken") }
            budgets.status shouldBe HttpStatusCode.OK
            budgets.body<List<ClubBudgetResponse>>().single { it.eventType == "LEAGUE" }.budgeted shouldBe 300
        }

    @Test
    fun `an administrator is allowed as an implicit points manager (#403)`() =
        withApp { client ->
            seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val adminToken = TestFirebaseAuth.mintToken(uid = "admin")

            client.get(urlString = "/api/v1/points/policies") { header(key = HttpHeaders.Authorization, value = "Bearer $adminToken") }
                .status shouldBe HttpStatusCode.OK
        }

    @Test
    fun `a plain player is forbidden from the points-budget API (#403)`() =
        withApp { client ->
            seedUser(uid = "player", roles = setOf(element = Capability.PLAYER))
            val playerToken = TestFirebaseAuth.mintToken(uid = "player")

            client.get(urlString = "/api/v1/points/policies") {
                header(key = HttpHeaders.Authorization, value = "Bearer $playerToken")
            }.status shouldBe HttpStatusCode.Forbidden
            client.get(urlString = "/api/v1/points/budgets") {
                header(key = HttpHeaders.Authorization, value = "Bearer $playerToken")
            }.status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `an unknown event type is a 400 and a negative budget is a 400 (#403)`() =
        withApp { client ->
            val pm = seedUser(uid = "pm", roles = setOf(Capability.PLAYER, Capability.POINTS_MANAGER))
            val pmToken = TestFirebaseAuth.mintToken(uid = "pm")
            val club = seedClub(ownerId = pm.id)

            client.put(urlString = "/api/v1/points/policies/NONSENSE") {
                header(key = HttpHeaders.Authorization, value = "Bearer $pmToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = SetPointsPolicyRequest(minPoints = 1, maxPoints = 5, maxValidityDays = 30))
            }.status shouldBe HttpStatusCode.BadRequest

            client.put(urlString = "/api/v1/clubs/$club/point-budgets/OPEN_PLAY") {
                header(key = HttpHeaders.Authorization, value = "Bearer $pmToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = SetClubBudgetRequest(budgetedPoints = -5))
            }.status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `the points-budget API requires a token (#403)`() =
        withApp { client ->
            client.get(urlString = "/api/v1/points/policies").status shouldBe HttpStatusCode.Unauthorized
        }

    @Test
    fun `points-summary is 200 for an owner and an admin, 403 otherwise, 401 anonymous (#403)`() =
        withApp { client ->
            val admin = seedUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
            val owner = seedUser(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
            seedUser(uid = "player", roles = setOf(element = Capability.PLAYER))
            val club = seedClub(ownerId = admin.id)
            ClubRepository().addOwner(clubId = club, userId = owner.id)

            suspend fun summary(uid: String) =
                client.get(urlString = "/api/v1/clubs/$club/points-summary") {
                    header(key = HttpHeaders.Authorization, value = "Bearer ${TestFirebaseAuth.mintToken(uid = uid)}")
                }

            summary(uid = "owner").let {
                it.status shouldBe HttpStatusCode.OK
                it.body<ClubPointsSummaryResponse>().utilization shouldHaveSize 3
            }
            summary(uid = "admin").status shouldBe HttpStatusCode.OK
            summary(uid = "player").status shouldBe HttpStatusCode.Forbidden
            // Anonymous (no token) is unauthorized — utilization never reaches the public surface.
            client.get(urlString = "/api/v1/clubs/$club/points-summary").status shouldBe HttpStatusCode.Unauthorized
        }
}
