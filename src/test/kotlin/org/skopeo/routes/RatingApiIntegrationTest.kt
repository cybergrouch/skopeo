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
import org.skopeo.dto.rating.PendingAssessmentPageResponse
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
 * End-to-end exercise of the rating/assessment API: an admin sets a rating, the user reads it,
 * the pending-assessment list surfaces unrated users, and non-admins are refused setting.
 */
class RatingApiIntegrationTest {
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

    private fun seedAdminToken(uid: String = "admin"): String {
        UserRepository().provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                    names = listOf(UserName(type = NameType.DISPLAY, value = "Admin")),
                    capabilities = setOf(Capability.PLAYER, Capability.ADMINISTRATOR),
                ),
        )
        return TestFirebaseAuth.mintToken(uid = uid)
    }

    private suspend fun HttpClient.provisionSelf(token: String): UserResponse =
        post(urlString = "/api/v1/users") {
            header(key = HttpHeaders.Authorization, value = "Bearer $token")
            contentType(type = ContentType.Application.Json)
            setBody(body = CreateUserRequest(proposedRating = "4.0", displayName = "Juan", dateOfBirth = "2000-01-01", sex = "Male"))
        }.body()

    @Test
    fun `admin sets a rating, the user reads it`() =
        withApp { client ->
            val adminToken = seedAdminToken()
            val userToken = TestFirebaseAuth.mintToken(uid = "fb-1")
            val user = client.provisionSelf(token = userToken)

            val set =
                client.put(urlString = "/api/v1/users/${user.id}/ratings") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = SetRatingRequest(value = "4.0"))
                }
            set.status shouldBe HttpStatusCode.OK
            set.body<UserRatingResponse>().let {
                it.value shouldBe "4.000000"
                it.level shouldBe "4.0"
            }

            // The owning player reads the band and gauge position, but NOT the exact rating (#64/#114).
            val ratings =
                client.get(urlString = "/api/v1/users/${user.id}/ratings") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $userToken")
                }
            ratings.status shouldBe HttpStatusCode.OK
            ratings.body<List<UserRatingResponse>>().single().let {
                it.value shouldBe null // raw rating withheld from the player
                it.level shouldBe "4.0" // band is visible
                it.bandPosition shouldBe 0.0 // position within the 4.0–4.5 band (at the floor)
            }
        }

    @Test
    fun `setting a rating from a band stores the band midpoint (#206)`() =
        withApp { client ->
            val adminToken = seedAdminToken()
            val user = client.provisionSelf(token = TestFirebaseAuth.mintToken(uid = "fb-band"))

            val set =
                client.put(urlString = "/api/v1/users/${user.id}/ratings") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = SetRatingRequest(band = "3.5"))
                }
            set.status shouldBe HttpStatusCode.OK
            set.body<UserRatingResponse>().let {
                it.value shouldBe "3.750000" // band 3.5 → midpoint 3.75
                it.level shouldBe "3.5" // still displays as the 3.5 band
            }
        }

    @Test
    fun `an out-of-range or missing rating is rejected at the route with a 400 (#116)`() =
        withApp { client ->
            val adminToken = seedAdminToken()
            val user = client.provisionSelf(token = TestFirebaseAuth.mintToken(uid = "fb-bad"))

            suspend fun setRating(request: SetRatingRequest) =
                client.put(urlString = "/api/v1/users/${user.id}/ratings") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                    contentType(type = ContentType.Application.Json)
                    setBody(body = request)
                }.status

            // Value outside the NTRP 1.0–7.0 range and a non-numeric value are both rejected.
            setRating(request = SetRatingRequest(value = "9.0")) shouldBe HttpStatusCode.BadRequest
            setRating(request = SetRatingRequest(value = "not-a-number")) shouldBe HttpStatusCode.BadRequest
            // A band selection is validated the same way; an out-of-range band is a 400.
            setRating(request = SetRatingRequest(band = "9.0")) shouldBe HttpStatusCode.BadRequest
            // Neither band nor value is a 400.
            setRating(request = SetRatingRequest()) shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `pending-assessment lists an unrated user (admin only)`() =
        withApp { client ->
            val adminToken = seedAdminToken()
            val userToken = TestFirebaseAuth.mintToken(uid = "fb-2")
            val user = client.provisionSelf(token = userToken)

            val pending =
                client.get(urlString = "/api/v1/users/pending-assessment") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
                }
            pending.status shouldBe HttpStatusCode.OK
            val page = pending.body<PendingAssessmentPageResponse>()
            page.items.any { it.userId == user.id } shouldBe true
            page.total shouldBe page.items.size // single small page: total equals what's returned

            // A normal user cannot list pending assessments.
            client.get(urlString = "/api/v1/users/pending-assessment") {
                header(key = HttpHeaders.Authorization, value = "Bearer $userToken")
            }.status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `a non-admin cannot set ratings and cannot read another user's`() =
        withApp { client ->
            seedAdminToken()
            val aliceToken = TestFirebaseAuth.mintToken(uid = "alice")
            val bobToken = TestFirebaseAuth.mintToken(uid = "bob")
            val alice = client.provisionSelf(token = aliceToken)
            client.provisionSelf(token = bobToken)

            client.put(urlString = "/api/v1/users/${alice.id}/ratings") {
                header(key = HttpHeaders.Authorization, value = "Bearer $aliceToken")
                contentType(type = ContentType.Application.Json)
                setBody(body = SetRatingRequest(value = "4.0"))
            }.status shouldBe HttpStatusCode.Forbidden

            client.get(urlString = "/api/v1/users/${alice.id}/ratings") {
                header(key = HttpHeaders.Authorization, value = "Bearer $bobToken")
            }.status shouldBe HttpStatusCode.Forbidden
        }
}
