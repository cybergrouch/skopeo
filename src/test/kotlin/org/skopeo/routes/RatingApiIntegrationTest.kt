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
import org.skopeo.dto.rating.PendingAssessmentResponse
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
        post("/api/v1/users") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CreateUserRequest(displayName = "Juan", dateOfBirth = "2000-01-01", sex = "Male"))
        }.body()

    @Test
    fun `admin sets a rating, the user reads it`() =
        withApp { client ->
            val adminToken = seedAdminToken()
            val userToken = TestFirebaseAuth.mintToken(uid = "fb-1")
            val user = client.provisionSelf(userToken)

            val set =
                client.put("/api/v1/users/${user.id}/ratings/NTRP") {
                    header(HttpHeaders.Authorization, "Bearer $adminToken")
                    contentType(ContentType.Application.Json)
                    setBody(SetRatingRequest(value = "4.0"))
                }
            set.status shouldBe HttpStatusCode.OK
            set.body<UserRatingResponse>().let {
                it.system shouldBe "NTRP"
                it.value shouldBe "4.000000"
                it.level shouldBe "4.0"
            }

            val ratings =
                client.get("/api/v1/users/${user.id}/ratings") {
                    header(HttpHeaders.Authorization, "Bearer $userToken")
                }
            ratings.status shouldBe HttpStatusCode.OK
            ratings.body<List<UserRatingResponse>>().single().system shouldBe "NTRP"
        }

    @Test
    fun `pending-assessment lists an unrated user (admin only)`() =
        withApp { client ->
            val adminToken = seedAdminToken()
            val userToken = TestFirebaseAuth.mintToken(uid = "fb-2")
            val user = client.provisionSelf(userToken)

            val pending =
                client.get("/api/v1/users/pending-assessment") {
                    header(HttpHeaders.Authorization, "Bearer $adminToken")
                }
            pending.status shouldBe HttpStatusCode.OK
            pending.body<List<PendingAssessmentResponse>>().any { it.userId == user.id } shouldBe true

            // A normal user cannot list pending assessments.
            client.get("/api/v1/users/pending-assessment") {
                header(HttpHeaders.Authorization, "Bearer $userToken")
            }.status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `a non-admin cannot set ratings and cannot read another user's`() =
        withApp { client ->
            seedAdminToken()
            val aliceToken = TestFirebaseAuth.mintToken(uid = "alice")
            val bobToken = TestFirebaseAuth.mintToken(uid = "bob")
            val alice = client.provisionSelf(aliceToken)
            client.provisionSelf(bobToken)

            client.put("/api/v1/users/${alice.id}/ratings/NTRP") {
                header(HttpHeaders.Authorization, "Bearer $aliceToken")
                contentType(ContentType.Application.Json)
                setBody(SetRatingRequest(value = "4.0"))
            }.status shouldBe HttpStatusCode.Forbidden

            client.get("/api/v1/users/${alice.id}/ratings") {
                header(HttpHeaders.Authorization, "Bearer $bobToken")
            }.status shouldBe HttpStatusCode.Forbidden
        }
}
