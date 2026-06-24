// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.match

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.dto.match.CreateFixtureRequest
import org.skopeo.dto.match.MatchResultRequest
import org.skopeo.dto.match.SetScoreRequest
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.MatchQuery
import org.skopeo.model.MatchStatus
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.MatchesTable
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.ForbiddenException
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.util.UUID

class MatchServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val ratings = RatingRepository()
    private val matchRepo = MatchRepository()
    private val service = MatchService(matches = matchRepo, ratings = ratings, users = users)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun provisionUser(
        uid: String,
        roles: Set<Capability> = setOf(Capability.PLAYER),
        rated: Boolean = false,
    ): User {
        val user =
            users.provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid,
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                        names = listOf(UserName(type = NameType.DISPLAY, value = uid)),
                        capabilities = roles,
                    ),
            )
        if (rated) {
            ratings.setRating(
                userId = user.id,
                rating = BigDecimal("4.0"),
                level = "4.0",
                confidence = BigDecimal("0.50"),
            )
        }
        return user
    }

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private fun fixtureRequest(
        p1: UUID,
        p2: UUID,
        date: String = "2026-01-01",
    ) = CreateFixtureRequest(
        matchType = "SINGLES",
        matchFormat = "BEST_OF_THREE",
        matchDate = date,
        team1 = listOf(p1.toString()),
        team2 = listOf(p2.toString()),
    )

    private fun straightSets() =
        MatchResultRequest(
            sets =
                listOf(
                    SetScoreRequest(team1Games = 6, team2Games = 4),
                    SetScoreRequest(team1Games = 6, team2Games = 3),
                ),
        )

    @Test
    fun `a host can create a fixture between rated players`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)

        val match = service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id))

        match.status shouldBe MatchStatus.SCHEDULED
        match.team1.userIds shouldBe listOf(p1.id)
    }

    @Test
    fun `non-staff cannot create a fixture`() {
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)

        shouldThrow<ForbiddenException> {
            service.createFixture(token = token(uid = "p1"), request = fixtureRequest(p1 = p1.id, p2 = p2.id))
        }
        shouldThrow<ForbiddenException> {
            service.createFixture(token = token(uid = "ghost"), request = fixtureRequest(p1 = p1.id, p2 = p2.id))
        }
    }

    @Test
    fun `a fixture rejects an unrated participant and bad composition`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val rated = provisionUser(uid = "p1", rated = true)
        val unrated = provisionUser(uid = "p2", rated = false)

        shouldThrow<IllegalArgumentException> {
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = rated.id, p2 = unrated.id))
        }
        // singles needs exactly one per side
        shouldThrow<IllegalArgumentException> {
            service.createFixture(
                token = token(uid = "host"),
                request =
                    fixtureRequest(p1 = rated.id, p2 = rated.id).copy(
                        team1 = listOf(rated.id.toString(), rated.id.toString()),
                    ),
            )
        }
    }

    @Test
    fun `uploading results derives the winner and completes the match`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match = service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id))

        val completed = service.uploadResult(token = token(uid = "host"), matchId = match.id, request = straightSets())

        completed.status shouldBe MatchStatus.COMPLETED
        completed.winnerTeamId shouldBe match.team1.teamId // won both sets
    }

    @Test
    fun `uploading results twice or to a disabled match is a conflict`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match = service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id))
        service.uploadResult(token = token(uid = "host"), matchId = match.id, request = straightSets())

        shouldThrow<MatchConflictException> {
            service.uploadResult(token = token(uid = "host"), matchId = match.id, request = straightSets())
        }

        val other = service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id))
        service.setActive(token = token(uid = "host"), matchId = other.id, active = false)
        shouldThrow<MatchConflictException> {
            service.uploadResult(token = token(uid = "host"), matchId = other.id, request = straightSets())
        }
    }

    @Test
    fun `a result with no clear winner is rejected`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match = service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id))

        // one set each → tie, no match winner
        shouldThrow<IllegalArgumentException> {
            service.uploadResult(
                token = token(uid = "host"),
                matchId = match.id,
                request =
                    MatchResultRequest(
                        sets =
                            listOf(
                                SetScoreRequest(team1Games = 6, team2Games = 0),
                                SetScoreRequest(team1Games = 0, team2Games = 6),
                            ),
                    ),
            )
        }
    }

    @Test
    fun `a rated match cannot be disabled`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match = service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id))
        service.uploadResult(token = token(uid = "host"), matchId = match.id, request = straightSets())
        // simulate the calculation trigger having rated it
        transaction {
            MatchesTable.update(where = { MatchesTable.id eq match.id }) {
                it[MatchesTable.ratedAt] = java.time.LocalDateTime.now()
            }
        }

        shouldThrow<MatchConflictException> {
            service.setActive(token = token(uid = "host"), matchId = match.id, active = false)
        }
    }

    @Test
    fun `reading a match is limited to participants and staff`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        provisionUser(uid = "outsider")
        val match = service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id))

        service.getById(token = token(uid = "p1"), matchId = match.id).id shouldBe match.id // participant
        service.getById(token = token(uid = "host"), matchId = match.id).id shouldBe match.id // staff
        shouldThrow<ForbiddenException> { service.getById(token = token(uid = "outsider"), matchId = match.id) }
        shouldThrow<MatchNotFoundException> { service.getById(token = token(uid = "host"), matchId = UUID.randomUUID()) }
    }

    @Test
    fun `a fixture rejects a disallowed match format`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)

        shouldThrow<IllegalArgumentException> {
            service.createFixture(
                token = token(uid = "host"),
                request = fixtureRequest(p1 = p1.id, p2 = p2.id).copy(matchFormat = "BEST_OF_ONE"),
            )
        }
    }

    @Test
    fun `a fixture rejects an unknown participant id`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val ghostId = UUID.randomUUID()

        shouldThrow<IllegalArgumentException> {
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = ghostId))
        }
    }

    @Test
    fun `a fixture rejects an inactive participant`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        users.deactivate(id = p2.id)

        shouldThrow<IllegalArgumentException> {
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id))
        }
    }

    @Test
    fun `uploading results to an unknown match is not found`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        shouldThrow<MatchNotFoundException> {
            service.uploadResult(token = token(uid = "host"), matchId = UUID.randomUUID(), request = straightSets())
        }
    }

    @Test
    fun `disabling an unknown match is not found`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        shouldThrow<MatchNotFoundException> {
            service.setActive(token = token(uid = "host"), matchId = UUID.randomUUID(), active = false)
        }
    }

    @Test
    fun `a host can disable then re-enable a match`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match = service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id))

        service.setActive(token = token(uid = "host"), matchId = match.id, active = false).isActive shouldBe false
        service.setActive(token = token(uid = "host"), matchId = match.id, active = true).isActive shouldBe true
    }

    @Test
    fun `reading a match refuses an unprovisioned caller`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match = service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id))

        shouldThrow<ForbiddenException> { service.getById(token = token(uid = "ghost"), matchId = match.id) }
    }

    @Test
    fun `oversight queries are staff-only, and a host is scoped to their own fixtures`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val completed = service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id))
        service.uploadResult(token = token(uid = "host"), matchId = completed.id, request = straightSets())
        val overdue =
            service.createFixture(
                token = token(uid = "host"),
                request = fixtureRequest(p1 = p1.id, p2 = p2.id, date = "2020-01-01"),
            )

        // Admin sees every match in the view.
        service.query(token = token(uid = "root"), view = MatchQuery.PENDING_CALCULATION).map { it.id } shouldContain completed.id
        service.query(token = token(uid = "root"), view = MatchQuery.AWAITING_RESULTS).map { it.id } shouldContain overdue.id
        // The host that created them can see their own.
        service.query(token = token(uid = "host"), view = MatchQuery.PENDING_CALCULATION).map { it.id } shouldContain completed.id
        service.query(token = token(uid = "host"), view = MatchQuery.AWAITING_RESULTS).map { it.id } shouldContain overdue.id
        // A non-staff player is refused.
        shouldThrow<ForbiddenException> { service.query(token = token(uid = "p1"), view = MatchQuery.PENDING_CALCULATION) }
    }
}
