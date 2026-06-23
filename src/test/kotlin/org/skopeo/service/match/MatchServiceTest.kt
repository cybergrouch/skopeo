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
import org.skopeo.model.RatingSystem
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
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = roles,
                ),
            )
        if (rated) ratings.setRating(user.id, RatingSystem.NTRP, BigDecimal("4.0"), "4.0", BigDecimal("0.50"))
        return user
    }

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private fun fixtureRequest(
        p1: UUID,
        p2: UUID,
        date: String = "2026-01-01",
    ) = CreateFixtureRequest(
        ratingSystem = "NTRP",
        matchType = "SINGLES",
        matchFormat = "BEST_OF_THREE",
        matchDate = date,
        team1 = listOf(p1.toString()),
        team2 = listOf(p2.toString()),
    )

    private fun straightSets() =
        MatchResultRequest(
            sets = listOf(SetScoreRequest(6, 4), SetScoreRequest(6, 3)),
        )

    @Test
    fun `a host can create a fixture between rated players`() {
        provisionUser("host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser("p1", rated = true)
        val p2 = provisionUser("p2", rated = true)

        val match = service.createFixture(token("host"), fixtureRequest(p1.id, p2.id))

        match.status shouldBe MatchStatus.SCHEDULED
        match.team1.userIds shouldBe listOf(p1.id)
    }

    @Test
    fun `non-staff cannot create a fixture`() {
        val p1 = provisionUser("p1", rated = true)
        val p2 = provisionUser("p2", rated = true)

        shouldThrow<ForbiddenException> { service.createFixture(token("p1"), fixtureRequest(p1.id, p2.id)) }
        shouldThrow<ForbiddenException> { service.createFixture(token("ghost"), fixtureRequest(p1.id, p2.id)) }
    }

    @Test
    fun `a fixture rejects an unrated participant and bad composition`() {
        provisionUser("host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val rated = provisionUser("p1", rated = true)
        val unrated = provisionUser("p2", rated = false)

        shouldThrow<IllegalArgumentException> { service.createFixture(token("host"), fixtureRequest(rated.id, unrated.id)) }
        // singles needs exactly one per side
        shouldThrow<IllegalArgumentException> {
            service.createFixture(
                token("host"),
                fixtureRequest(rated.id, rated.id).copy(team1 = listOf(rated.id.toString(), rated.id.toString())),
            )
        }
    }

    @Test
    fun `uploading results derives the winner and completes the match`() {
        provisionUser("host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser("p1", rated = true)
        val p2 = provisionUser("p2", rated = true)
        val match = service.createFixture(token("host"), fixtureRequest(p1.id, p2.id))

        val completed = service.uploadResult(token("host"), match.id, straightSets())

        completed.status shouldBe MatchStatus.COMPLETED
        completed.winnerTeamId shouldBe match.team1.teamId // won both sets
    }

    @Test
    fun `uploading results twice or to a disabled match is a conflict`() {
        provisionUser("host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser("p1", rated = true)
        val p2 = provisionUser("p2", rated = true)
        val match = service.createFixture(token("host"), fixtureRequest(p1.id, p2.id))
        service.uploadResult(token("host"), match.id, straightSets())

        shouldThrow<MatchConflictException> { service.uploadResult(token("host"), match.id, straightSets()) }

        val other = service.createFixture(token("host"), fixtureRequest(p1.id, p2.id))
        service.setActive(token("host"), other.id, active = false)
        shouldThrow<MatchConflictException> { service.uploadResult(token("host"), other.id, straightSets()) }
    }

    @Test
    fun `a result with no clear winner is rejected`() {
        provisionUser("host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser("p1", rated = true)
        val p2 = provisionUser("p2", rated = true)
        val match = service.createFixture(token("host"), fixtureRequest(p1.id, p2.id))

        // one set each → tie, no match winner
        shouldThrow<IllegalArgumentException> {
            service.uploadResult(token("host"), match.id, MatchResultRequest(listOf(SetScoreRequest(6, 0), SetScoreRequest(0, 6))))
        }
    }

    @Test
    fun `a rated match cannot be disabled`() {
        provisionUser("host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser("p1", rated = true)
        val p2 = provisionUser("p2", rated = true)
        val match = service.createFixture(token("host"), fixtureRequest(p1.id, p2.id))
        service.uploadResult(token("host"), match.id, straightSets())
        // simulate the calculation trigger having rated it
        transaction {
            MatchesTable.update({ MatchesTable.id eq match.id }) {
                it[MatchesTable.ratedAt] = java.time.LocalDateTime.now()
            }
        }

        shouldThrow<MatchConflictException> { service.setActive(token("host"), match.id, active = false) }
    }

    @Test
    fun `reading a match is limited to participants and staff`() {
        provisionUser("host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser("p1", rated = true)
        val p2 = provisionUser("p2", rated = true)
        provisionUser("outsider")
        val match = service.createFixture(token("host"), fixtureRequest(p1.id, p2.id))

        service.getById(token("p1"), match.id).id shouldBe match.id // participant
        service.getById(token("host"), match.id).id shouldBe match.id // staff
        shouldThrow<ForbiddenException> { service.getById(token("outsider"), match.id) }
        shouldThrow<MatchNotFoundException> { service.getById(token("host"), UUID.randomUUID()) }
    }

    @Test
    fun `oversight queries are admin-only`() {
        provisionUser("root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provisionUser("host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser("p1", rated = true)
        val p2 = provisionUser("p2", rated = true)
        val completed = service.createFixture(token("host"), fixtureRequest(p1.id, p2.id))
        service.uploadResult(token("host"), completed.id, straightSets())
        val overdue = service.createFixture(token("host"), fixtureRequest(p1.id, p2.id, date = "2020-01-01"))

        service.query(token("root"), MatchQuery.PENDING_CALCULATION).map { it.id } shouldContain completed.id
        service.query(token("root"), MatchQuery.AWAITING_RESULTS).map { it.id } shouldContain overdue.id
        shouldThrow<ForbiddenException> { service.query(token("host"), MatchQuery.PENDING_CALCULATION) }
    }
}
