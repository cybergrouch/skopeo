// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.kotest.matchers.nulls.shouldNotBeNull
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
import org.skopeo.dto.rating.SetRatingRequest
import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.dto.user.UserResponse
import org.skopeo.dto.user.UserSummaryPageResponse
import org.skopeo.dto.user.UserSummaryResponse
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.CreateFixtureCommand
import org.skopeo.model.MatchSetResult
import org.skopeo.model.MatchType
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.TeamType
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.module
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.TestFirebaseAuth
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * End-to-end exercise of user search: staff (HOST/ADMINISTRATOR) can find users by name
 * (case-insensitive, partial); a plain player is refused; a missing query is a 400.
 */
class UserSearchApiIntegrationTest {
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

    private fun seedStaff(
        uid: String,
        roles: Set<Capability>,
    ): String {
        UserRepository().provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                    names = listOf(UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = roles + Capability.PLAYER,
                ),
        )
        return TestFirebaseAuth.mintToken(uid = uid)
    }

    private suspend fun HttpClient.provisionNamed(
        uid: String,
        displayName: String,
    ): UserResponse =
        post(urlString = "/api/v1/users") {
            header(key = HttpHeaders.Authorization, value = "Bearer ${TestFirebaseAuth.mintToken(uid = uid)}")
            contentType(type = ContentType.Application.Json)
            setBody(body = CreateUserRequest(proposedRating = "4.0", displayName = displayName, dateOfBirth = "2000-01-01", sex = "Male"))
        }.body()

    private suspend fun HttpClient.lookup(
        token: String,
        params: String,
    ) = get(urlString = "/api/v1/users${if (params.isEmpty()) "" else "?$params"}") {
        header(key = HttpHeaders.Authorization, value = "Bearer $token")
    }

    /** Query with properly-encoded filter params (handles interval brackets/parens). */
    private suspend fun HttpClient.searchWith(
        token: String,
        vararg filters: Pair<String, String>,
    ) = get(urlString = "/api/v1/users") {
        header(key = HttpHeaders.Authorization, value = "Bearer $token")
        url { filters.forEach { (key, value) -> parameters.append(name = key, value = value) } }
    }

    private suspend fun HttpClient.provisionProfile(
        uid: String,
        displayName: String,
        sex: String = "Male",
        dateOfBirth: String = "2000-01-01",
    ): UserResponse =
        post(urlString = "/api/v1/users") {
            header(key = HttpHeaders.Authorization, value = "Bearer ${TestFirebaseAuth.mintToken(uid = uid)}")
            contentType(type = ContentType.Application.Json)
            setBody(body = CreateUserRequest(proposedRating = "4.0", displayName = displayName, sex = sex, dateOfBirth = dateOfBirth))
        }.body()

    private suspend fun HttpClient.rate(
        adminToken: String,
        userId: String,
        value: String,
    ) = put(urlString = "/api/v1/users/$userId/ratings") {
        header(key = HttpHeaders.Authorization, value = "Bearer $adminToken")
        contentType(type = ContentType.Application.Json)
        setBody(body = SetRatingRequest(value = value))
    }

    @Test
    fun `a host finds users by partial, case-insensitive name`() =
        withApp { client ->
            val host = seedStaff(uid = "host", roles = setOf(Capability.HOST))
            client.provisionNamed(uid = "u1", displayName = "Alice")
            client.provisionNamed(uid = "u2", displayName = "Alicia")
            client.provisionNamed(uid = "u3", displayName = "Bob")

            val results = client.lookup(token = host, params = "name=ALI").body<List<UserSummaryResponse>>()

            results.map { it.displayName }.toSet() shouldBe setOf("Alice", "Alicia")
        }

    @Test
    fun `the capability filter returns only users holding that capability (#317)`() =
        withApp { client ->
            val host = seedStaff(uid = "host", roles = setOf(element = Capability.HOST))
            seedStaff(uid = "clubby", roles = setOf(element = Capability.CLUB_OWNER))
            client.provisionNamed(uid = "u1", displayName = "PlainPlayer")

            val names =
                client.lookup(token = host, params = "capability=CLUB_OWNER").body<List<UserSummaryResponse>>().map { it.displayName }
            names.contains(element = "clubby") shouldBe true
            names.contains(element = "PlainPlayer") shouldBe false

            // An unknown capability value is a 400.
            client.lookup(token = host, params = "capability=NOPE").status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `name search is accent-insensitive, finding accented and plain spellings alike`() =
        withApp { client ->
            val host = seedStaff(uid = "host", roles = setOf(Capability.HOST))
            client.provisionNamed(uid = "u1", displayName = "Maria Garcia")
            client.provisionNamed(uid = "u2", displayName = "María García")
            client.provisionNamed(uid = "u3", displayName = "Bob")

            // A plain-ASCII query finds both Marias (the accented one no longer slips through).
            val plain = client.lookup(token = host, params = "name=maria").body<List<UserSummaryResponse>>()
            plain.map { it.displayName }.toSet() shouldBe setOf("Maria Garcia", "María García")

            // And an accented query also finds the plain spelling.
            val accented = client.lookup(token = host, params = "name=garcía").body<List<UserSummaryResponse>>()
            accented.map { it.displayName }.toSet() shouldBe setOf("Maria Garcia", "María García")
        }

    @Test
    fun `name search tolerates a misspelling`() =
        withApp { client ->
            val host = seedStaff(uid = "host", roles = setOf(Capability.HOST))
            client.provisionNamed(uid = "u1", displayName = "Alice")
            client.provisionNamed(uid = "u2", displayName = "Bob")

            val results = client.lookup(token = host, params = "name=Alyce").body<List<UserSummaryResponse>>()

            results.map { it.displayName } shouldBe listOf("Alice")
        }

    @Test
    fun `a profile with several matching names appears once, and non-display names are searched`() =
        withApp { client ->
            val host = seedStaff(uid = "host", roles = setOf(Capability.HOST))
            // One profile whose FIRST and DISPLAY names both match "john".
            UserRepository().provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = "multi",
                        identity =
                            UserIdentity(provider = AuthProvider.GOOGLE, providerUid = "multi", isPrimary = true),
                        names =
                            listOf(
                                UserName(type = NameType.FIRST, value = "Johnathan"),
                                UserName(type = NameType.DISPLAY, value = "Johnny"),
                            ),
                        capabilities = setOf(Capability.PLAYER),
                    ),
            )

            val results = client.lookup(token = host, params = "name=john").body<List<UserSummaryResponse>>()

            results.count { it.displayName == "Johnny" } shouldBe 1
        }

    @Test
    fun `an admin can search too`() =
        withApp { client ->
            val admin = seedStaff(uid = "admin", roles = setOf(Capability.ADMINISTRATOR))
            client.provisionNamed(uid = "u1", displayName = "Charlie")

            val response = client.lookup(token = admin, params = "name=char")
            response.status shouldBe HttpStatusCode.OK
            response.body<List<UserSummaryResponse>>().single().displayName shouldBe "Charlie"
        }

    @Test
    fun `a host resolves known ids to summaries`() =
        withApp { client ->
            val host = seedStaff(uid = "host", roles = setOf(Capability.HOST))
            val alice = client.provisionNamed(uid = "u1", displayName = "Alice")
            val bob = client.provisionNamed(uid = "u2", displayName = "Bob")
            client.provisionNamed(uid = "u3", displayName = "Carol")

            val results =
                client
                    .lookup(token = host, params = "ids=${alice.id},${bob.id}")
                    .body<List<UserSummaryResponse>>()

            results.map { it.displayName }.toSet() shouldBe setOf("Alice", "Bob")
        }

    @Test
    fun `a default player can search but cannot resolve ids (#107)`() =
        withApp { client ->
            seedStaff(uid = "admin", roles = setOf(Capability.ADMINISTRATOR))
            val player = TestFirebaseAuth.mintToken(uid = "p1")
            val p1 = client.provisionNamed(uid = "p1", displayName = "Player One")

            // A default sign-up is a RESEARCHER, so player research (search) is allowed (#107)...
            client.lookup(token = player, params = "name=player").status shouldBe HttpStatusCode.OK
            // ...but id-resolution stays HOST/ADMINISTRATOR only.
            client.lookup(token = player, params = "ids=${p1.id}").status shouldBe HttpStatusCode.Forbidden
        }

    @Test
    fun `an unprovisioned caller is forbidden`() =
        withApp { client ->
            // A valid token whose uid was never provisioned — no caller record at all.
            client.lookup(token = TestFirebaseAuth.mintToken(uid = "ghost"), params = "name=alice").status shouldBe
                HttpStatusCode.Forbidden
        }

    @Test
    fun `an empty ids list is a 400`() =
        withApp { client ->
            val host = seedStaff(uid = "host", roles = setOf(Capability.HOST))
            client.lookup(token = host, params = "ids=").status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `neither name nor ids is a 400`() =
        withApp { client ->
            val host = seedStaff(uid = "host", roles = setOf(Capability.HOST))
            client.lookup(token = host, params = "").status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `both name and ids is a 400`() =
        withApp { client ->
            val host = seedStaff(uid = "host", roles = setOf(Capability.HOST))
            client.lookup(token = host, params = "name=a&ids=${java.util.UUID.randomUUID()}").status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `a blank name is a 400`() =
        withApp { client ->
            val host = seedStaff(uid = "host", roles = setOf(Capability.HOST))
            client.lookup(token = host, params = "name=%20").status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `a malformed id is a 400`() =
        withApp { client ->
            val host = seedStaff(uid = "host", roles = setOf(Capability.HOST))
            client.lookup(token = host, params = "ids=not-a-uuid").status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `filters by sex`() =
        withApp { client ->
            val host = seedStaff(uid = "host", roles = setOf(Capability.HOST))
            client.provisionProfile(uid = "u1", displayName = "Alice", sex = "Female")
            client.provisionProfile(uid = "u2", displayName = "Bob", sex = "Male")

            val results = client.searchWith(token = host, "sex" to "Female").body<List<UserSummaryResponse>>()

            results.map { it.displayName } shouldBe listOf("Alice")
        }

    @Test
    fun `filters by age range`() =
        withApp { client ->
            val host = seedStaff(uid = "host", roles = setOf(Capability.HOST))
            client.provisionProfile(uid = "u1", displayName = "Twenties", dateOfBirth = "2000-01-01") // ~26
            client.provisionProfile(uid = "u2", displayName = "Teen", dateOfBirth = "2012-01-01") // ~14
            client.provisionProfile(uid = "u3", displayName = "Older", dateOfBirth = "1985-01-01") // ~41

            val results = client.searchWith(token = host, "age" to "(20,30]").body<List<UserSummaryResponse>>()

            results.map { it.displayName } shouldBe listOf("Twenties")
        }

    @Test
    fun `filters by NTRP rating range`() =
        withApp { client ->
            val admin = seedStaff(uid = "admin", roles = setOf(Capability.ADMINISTRATOR))
            val a = client.provisionProfile(uid = "u1", displayName = "Mid")
            val b = client.provisionProfile(uid = "u2", displayName = "High")
            client.rate(adminToken = admin, userId = a.id, value = "4.0")
            client.rate(adminToken = admin, userId = b.id, value = "5.5")

            val results = client.searchWith(token = admin, "rating" to "[3.5,4.5)").body<List<UserSummaryResponse>>()

            results.map { it.displayName } shouldBe listOf("Mid")
        }

    @Test
    fun `combines name and sex filters`() =
        withApp { client ->
            val host = seedStaff(uid = "host", roles = setOf(Capability.HOST))
            client.provisionProfile(uid = "u1", displayName = "Alice", sex = "Female")
            client.provisionProfile(uid = "u2", displayName = "Alicia", sex = "Male")

            val results =
                client.searchWith(token = host, "name" to "ali", "sex" to "Female").body<List<UserSummaryResponse>>()

            results.map { it.displayName } shouldBe listOf("Alice")
        }

    @Test
    fun `ids cannot be combined with filters`() =
        withApp { client ->
            val host = seedStaff(uid = "host", roles = setOf(Capability.HOST))
            client
                .searchWith(token = host, "ids" to java.util.UUID.randomUUID().toString(), "sex" to "Male")
                .status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `an invalid sex value is a 400`() =
        withApp { client ->
            val host = seedStaff(uid = "host", roles = setOf(Capability.HOST))
            client.searchWith(token = host, "sex" to "Other").status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `a malformed interval is a 400`() =
        withApp { client ->
            val host = seedStaff(uid = "host", roles = setOf(Capability.HOST))
            client.searchWith(token = host, "rating" to "3.0,4.0").status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `paged search returns items with the total and honors limit and offset (#232)`() =
        withApp { client ->
            val host = seedStaff(uid = "host", roles = setOf(Capability.HOST))
            (1..3).forEach { n -> client.provisionNamed(uid = "u$n", displayName = "Alice $n") }

            val first =
                client.get(urlString = "/api/v1/users/search?name=alice&limit=2&offset=0") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $host")
                }.body<UserSummaryPageResponse>()
            first.total shouldBe 3 // all three match...
            first.items.size shouldBe 2 // ...but only the requested page of 2 is returned

            val second =
                client.get(urlString = "/api/v1/users/search?name=alice&limit=2&offset=2") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $host")
                }.body<UserSummaryPageResponse>()
            second.total shouldBe 3
            second.items.size shouldBe 1 // the remainder
        }

    @Test
    fun `paged search results carry each player's decided win-loss record (#342)`() =
        withApp { client ->
            val host = seedStaff(uid = "host", roles = setOf(Capability.HOST))
            val alice = client.provisionNamed(uid = "alice", displayName = "Alice Racer")
            val bob = client.provisionNamed(uid = "bob", displayName = "Bob Racer")

            // Alice beats Bob in one completed singles match (seeded directly).
            val matches = MatchRepository()
            val match =
                matches.createFixture(
                    command =
                        CreateFixtureCommand(
                            matchFormat = TeamType.SINGLES,
                            matchType = MatchType.OPEN_PLAY,
                            matchDate = LocalDate.of(2026, 1, 1),
                            team1UserIds = listOf(element = UUID.fromString(alice.id)),
                            team2UserIds = listOf(element = UUID.fromString(bob.id)),
                            team1Name = "T1",
                            team2Name = "T2",
                            createdBy = UUID.fromString(alice.id),
                        ),
                )
            matches.addResult(
                matchId = match.id,
                sets =
                    listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 0, winnerTeamId = match.team1.teamId)),
                winnerTeamId = match.team1.teamId,
                recordedBy = UUID.fromString(alice.id),
                completedAt = LocalDateTime.now(),
            )

            val page =
                client.get(urlString = "/api/v1/users/search?name=Racer&limit=25&offset=0") {
                    header(key = HttpHeaders.Authorization, value = "Bearer $host")
                }.body<UserSummaryPageResponse>()

            val aliceRecord = page.items.single { it.id == alice.id }.record.shouldNotBeNull()
            aliceRecord.wins shouldBe 1
            aliceRecord.losses shouldBe 0
            aliceRecord.total shouldBe 1
            val bobRecord = page.items.single { it.id == bob.id }.record.shouldNotBeNull()
            bobRecord.wins shouldBe 0
            bobRecord.losses shouldBe 1
        }

    @Test
    fun `paged search is refused for an unprovisioned caller (#232)`() =
        withApp { client ->
            // Mirrors the list search: no caller record → no research access.
            client
                .get(urlString = "/api/v1/users/search?name=alice") {
                    header(key = HttpHeaders.Authorization, value = "Bearer ${TestFirebaseAuth.mintToken(uid = "ghost")}")
                }.status shouldBe HttpStatusCode.Forbidden
        }
}
