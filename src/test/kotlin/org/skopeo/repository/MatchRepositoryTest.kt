// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.CreateEventCommand
import org.skopeo.model.CreateFixtureCommand
import org.skopeo.model.MatchSetResult
import org.skopeo.model.MatchStatus
import org.skopeo.model.MatchType
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
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
    private val events = EventRepository()

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
                matchFormat = TeamType.SINGLES,
                matchType = MatchType.OPEN_PLAY,
                matchDate = date,
                team1UserIds = listOf(u1),
                team2UserIds = listOf(u2),
                team1Name = "T1",
                team2Name = "T2",
                createdBy = u1,
            ),
    )

    /** Create a COMPLETED (pending-calculation) match on [matchDate], optionally under [eventId]. */
    private fun completedMatch(
        u1: UUID,
        u2: UUID,
        matchDate: LocalDate,
        eventId: UUID? = null,
    ): UUID {
        val match =
            matches.createFixture(
                command =
                    CreateFixtureCommand(
                        matchFormat = TeamType.SINGLES,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = matchDate,
                        team1UserIds = listOf(element = u1),
                        team2UserIds = listOf(element = u2),
                        team1Name = "T1",
                        team2Name = "T2",
                        createdBy = u1,
                        eventId = eventId,
                    ),
            )
        matches.addResult(
            matchId = match.id,
            sets =
                listOf(
                    MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 0, winnerTeamId = match.team1.teamId),
                    MatchSetResult(setNumber = 2, team1Games = 6, team2Games = 0, winnerTeamId = match.team1.teamId),
                ),
            winnerTeamId = match.team1.teamId,
            recordedBy = u1,
            completedAt = LocalDateTime.now(),
        )
        return match.id
    }

    private fun event(
        creator: UUID,
        endDate: LocalDate,
        members: List<UUID>,
    ): UUID =
        events.create(
            command =
                CreateEventCommand(
                    name = "E-$endDate",
                    startDate = endDate.minusDays(2),
                    endDate = endDate,
                    participantIds = members,
                    createdBy = creator,
                ),
        ).id

    @Test
    fun `pending-calculation orders by event end date, interleaves eventless by match date, honors override (#335)`() {
        val u1 = newUser(uid = "u1")
        val u2 = newUser(uid = "u2")
        val eventA = event(creator = u1, endDate = LocalDate.of(2026, 1, 10), members = listOf(u1, u2))
        val eventB = event(creator = u1, endDate = LocalDate.of(2026, 1, 20), members = listOf(u1, u2))
        val a1 = completedMatch(u1 = u1, u2 = u2, matchDate = LocalDate.of(2026, 1, 8), eventId = eventA)
        val b1 = completedMatch(u1 = u1, u2 = u2, matchDate = LocalDate.of(2026, 1, 18), eventId = eventB)
        // An eventless match played between the two events' end dates.
        val open = completedMatch(u1 = u1, u2 = u2, matchDate = LocalDate.of(2026, 1, 15))

        // Default: event A (ends 1/10) → the eventless match (1/15) → event B (ends 1/20).
        matches.listPendingCalculation().map { it.id } shouldBe listOf(a1, open, b1)

        // Admin bumps event B ahead of everything by giving it the lowest processing key.
        events.setCalcPriority(id = eventB, priority = 0.0)
        matches.listPendingCalculation().map { it.id } shouldBe listOf(b1, a1, open)
    }

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
        matches.findById(matchId = match.id).shouldBeRight()
    }

    @Test
    fun `listBetweenUsers returns matches between exactly two players, newest first (#188)`() {
        val a = newUser(uid = "a")
        val b = newUser(uid = "b")
        val c = newUser(uid = "c")
        val older = fixture(u1 = a, u2 = b, date = LocalDate.of(2026, 1, 1))
        val newer = fixture(u1 = b, u2 = a, date = LocalDate.of(2026, 2, 1)) // reversed sides still count
        fixture(u1 = a, u2 = c, date = LocalDate.of(2026, 1, 15)) // a vs c — not the pair

        matches.listBetweenUsers(userIdA = a, userIdB = b).map { it.id } shouldBe listOf(newer.id, older.id)
    }

    @Test
    fun `listBetweenUsers is empty when a player has no matches (#188)`() {
        val a = newUser(uid = "a")
        val b = newUser(uid = "b")
        fixture(u1 = a, u2 = b)
        val loner = newUser(uid = "loner")

        // Empty whether the player with no matches is the first or second argument.
        matches.listBetweenUsers(userIdA = a, userIdB = loner).shouldBe(expected = emptyList())
        matches.listBetweenUsers(userIdA = loner, userIdB = a).shouldBe(expected = emptyList())
    }

    @Test
    fun `addResult records sets, winner, and completes the match`() {
        val u1 = newUser(uid = "u1")
        val u2 = newUser(uid = "u2")
        val match = fixture(u1 = u1, u2 = u2)
        val t1 = match.team1.teamId

        val completed =
            matches
                .addResult(
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
                ).shouldBeRight()

        completed.status shouldBe MatchStatus.COMPLETED
        completed.winnerTeamId shouldBe t1
        completed.completedAt.shouldNotBeNull()
        completed.sets.size shouldBe 2
        completed.sets[1].tiebreakTeam1Points shouldBe 7
    }

    @Test
    fun `findById on an unknown match is not found`() {
        matches.findById(matchId = UUID.randomUUID()).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `addResult on an unknown match is not found`() {
        matches
            .addResult(
                matchId = UUID.randomUUID(),
                sets = listOf(MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 0, winnerTeamId = UUID.randomUUID())),
                winnerTeamId = UUID.randomUUID(),
                recordedBy = UUID.randomUUID(),
                completedAt = LocalDateTime.now(),
            ).shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `addResult treats a half-specified tiebreak as no tiebreak`() {
        val u1 = newUser(uid = "u1")
        val u2 = newUser(uid = "u2")
        val match = fixture(u1 = u1, u2 = u2)
        val t1 = match.team1.teamId

        // only one tiebreak side supplied -> hasTb is false (short-circuits before persisting a tiebreak)
        val completed =
            matches
                .addResult(
                    matchId = match.id,
                    sets =
                        listOf(
                            MatchSetResult(
                                setNumber = 1,
                                team1Games = 7,
                                team2Games = 6,
                                winnerTeamId = t1,
                                tiebreakTeam1Points = 7,
                                tiebreakTeam2Points = null,
                            ),
                        ),
                    winnerTeamId = t1,
                    recordedBy = u1,
                    completedAt = LocalDateTime.now(),
                ).shouldBeRight()

        completed.sets.size shouldBe 1
        completed.sets[0].tiebreakTeam1Points.shouldBeNull()
        completed.sets[0].tiebreakTeam2Points.shouldBeNull()
    }

    @Test
    fun `setActive on an unknown match is not found`() {
        matches
            .setActive(matchId = UUID.randomUUID(), active = false, disabledAt = LocalDateTime.now())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `findById tolerates a match whose creator was cleared`() {
        val match = fixture(u1 = newUser(uid = "c1"), u2 = newUser(uid = "c2"))
        transaction {
            MatchesTable.update(where = { MatchesTable.id eq match.id }) { it[createdBy] = null }
        }

        val reloaded = matches.findById(matchId = match.id).shouldBeRight()

        reloaded.createdBy.shouldBeNull()
    }

    @Test
    fun `setActive disables and re-enables`() {
        val match = fixture(u1 = newUser(uid = "u1"), u2 = newUser(uid = "u2"))

        matches.setActive(matchId = match.id, active = false, disabledAt = LocalDateTime.now()).shouldBeRight().let {
            it.isActive.shouldBeFalse()
        }
        matches.setActive(matchId = match.id, active = true, disabledAt = null).shouldBeRight().isActive.shouldBeTrue()
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
    fun `awaiting-results lists scheduled fixtures regardless of match date`() {
        val overdue = fixture(u1 = newUser(uid = "o1"), u2 = newUser(uid = "o2"), date = LocalDate.of(2020, 1, 1))
        val today = fixture(u1 = newUser(uid = "t1"), u2 = newUser(uid = "t2"), date = LocalDate.now())
        val future = fixture(u1 = newUser(uid = "f1"), u2 = newUser(uid = "f2"), date = LocalDate.of(2999, 1, 1))

        val awaiting = matches.listAwaitingResults().map { it.id }
        awaiting shouldContain overdue.id
        awaiting shouldContain today.id
        awaiting shouldContain future.id
    }
}
