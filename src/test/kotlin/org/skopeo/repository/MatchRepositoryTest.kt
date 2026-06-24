// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.CreateFixtureCommand
import org.skopeo.model.MatchFormat
import org.skopeo.model.MatchSetResult
import org.skopeo.model.MatchStatus
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.TeamType
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.testsupport.PostgresTestDatabase
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class MatchRepositoryTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val matches = MatchRepository()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun newUser(uid: String): UUID =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(UserName(type = NameType.DISPLAY, value = uid)),
                ),
        ).id

    private fun fixture(
        u1: UUID,
        u2: UUID,
        date: LocalDate = LocalDate.of(2026, 1, 1),
    ) = matches.createFixture(
        command =
            CreateFixtureCommand(
                matchType = TeamType.SINGLES,
                matchFormat = MatchFormat.BEST_OF_THREE,
                matchDate = date,
                team1UserIds = listOf(u1),
                team2UserIds = listOf(u2),
                team1Name = "T1",
                team2Name = "T2",
                createdBy = u1,
            ),
    )

    @Test
    fun `createFixture stores a scheduled match with its teams`() {
        val u1 = newUser(uid = "u1")
        val u2 = newUser(uid = "u2")

        val match = fixture(u1 = u1, u2 = u2)

        match.status shouldBe MatchStatus.SCHEDULED
        match.team1.userIds shouldBe listOf(u1)
        match.team2.userIds shouldBe listOf(u2)
        match.winnerTeamId.shouldBeNull()
        match.sets.shouldBe(expected = emptyList())
        matches.findById(matchId = match.id).shouldNotBeNull()
    }

    @Test
    fun `addResult records sets, winner, and completes the match`() {
        val u1 = newUser(uid = "u1")
        val u2 = newUser(uid = "u2")
        val match = fixture(u1 = u1, u2 = u2)
        val t1 = match.team1.teamId

        val completed =
            matches.addResult(
                matchId = match.id,
                sets =
                    listOf(
                        MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 4, winnerTeamId = t1),
                        MatchSetResult(
                            setNumber = 2,
                            team1Games = 7,
                            team2Games = 6,
                            winnerTeamId = t1,
                            tiebreakTeam1Points = 7,
                            tiebreakTeam2Points = 5,
                        ),
                    ),
                winnerTeamId = t1,
                recordedBy = u1,
                completedAt = LocalDateTime.now(),
            )

        completed.shouldNotBeNull()
        completed.status shouldBe MatchStatus.COMPLETED
        completed.winnerTeamId shouldBe t1
        completed.completedAt.shouldNotBeNull()
        completed.sets.size shouldBe 2
        completed.sets[1].tiebreakTeam1Points shouldBe 7
    }

    @Test
    fun `setActive disables and re-enables`() {
        val match = fixture(u1 = newUser(uid = "u1"), u2 = newUser(uid = "u2"))

        matches.setActive(matchId = match.id, active = false, disabledAt = LocalDateTime.now())!!.let {
            it.isActive.shouldBeFalse()
        }
        matches.setActive(matchId = match.id, active = true, disabledAt = null)!!.isActive.shouldBeTrue()
    }

    @Test
    fun `pending-calculation lists completed unrated matches only`() {
        val completed = fixture(u1 = newUser(uid = "a1"), u2 = newUser(uid = "a2"))
        val t1 = completed.team1.teamId
        matches.addResult(
            matchId = completed.id,
            sets =
                listOf(
                    MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 0, winnerTeamId = t1),
                    MatchSetResult(setNumber = 2, team1Games = 6, team2Games = 0, winnerTeamId = t1),
                ),
            winnerTeamId = t1,
            recordedBy = completed.team1.userIds.first(),
            completedAt = LocalDateTime.now(),
        )
        val scheduled = fixture(u1 = newUser(uid = "b1"), u2 = newUser(uid = "b2"))

        val pending = matches.listPendingCalculation().map { it.id }
        pending shouldContain completed.id
        pending shouldNotContain scheduled.id
    }

    @Test
    fun `awaiting-results lists overdue scheduled fixtures`() {
        val overdue = fixture(u1 = newUser(uid = "o1"), u2 = newUser(uid = "o2"), date = LocalDate.of(2020, 1, 1))
        val future = fixture(u1 = newUser(uid = "f1"), u2 = newUser(uid = "f2"), date = LocalDate.of(2999, 1, 1))

        val awaiting = matches.listAwaitingResults(asOf = LocalDate.of(2026, 6, 23)).map { it.id }
        awaiting shouldContain overdue.id
        awaiting shouldNotContain future.id
    }
}
