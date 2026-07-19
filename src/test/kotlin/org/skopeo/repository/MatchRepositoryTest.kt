// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldBeEmpty
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
import org.skopeo.model.WeightClass
import org.skopeo.model.WinLossRecord
import org.skopeo.model.WindowMatch
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
        completedAt: LocalDateTime = LocalDateTime.now(),
        matchType: MatchType = MatchType.OPEN_PLAY,
    ): UUID {
        val match =
            matches.createFixture(
                command =
                    CreateFixtureCommand(
                        matchFormat = TeamType.SINGLES,
                        matchType = matchType,
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
            completedAt = completedAt,
        )
        return match.id
    }

    /** As [completedMatch] but recorded by an explicit [creator] — for creator/event scoping tests. */
    private fun completedMatchCreatedBy(
        creator: UUID,
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
                        createdBy = creator,
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
            recordedBy = creator,
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

    /** A COMPLETED doubles match (two a side) where [winners] beat [losers]. */
    private fun completedDoubles(
        winners: List<UUID>,
        losers: List<UUID>,
    ): UUID {
        val match =
            matches.createFixture(
                command =
                    CreateFixtureCommand(
                        matchFormat = TeamType.DOUBLES,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = LocalDate.of(2026, 2, 1),
                        team1UserIds = winners,
                        team2UserIds = losers,
                        team1Name = "T1",
                        team2Name = "T2",
                        createdBy = winners.first(),
                    ),
            )
        matches.addResult(
            matchId = match.id,
            sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 0, winnerTeamId = match.team1.teamId)),
            winnerTeamId = match.team1.teamId,
            recordedBy = winners.first(),
            completedAt = LocalDateTime.now(),
        )
        return match.id
    }

    @Test
    fun `winLossByUsers aggregates decided singles and doubles, ignores undecided, omits the matchless (#342)`() {
        val u1 = newUser(uid = "u1")
        val u2 = newUser(uid = "u2")
        val u3 = newUser(uid = "u3")
        val u4 = newUser(uid = "u4")
        val u5 = newUser(uid = "u5") // plays nothing

        completedMatch(u1 = u1, u2 = u2, matchDate = LocalDate.of(2026, 1, 1)) // u1 beats u2 (singles)
        completedMatch(u1 = u2, u2 = u1, matchDate = LocalDate.of(2026, 1, 8)) // u2 beats u1 (singles)
        completedDoubles(winners = listOf(u1, u3), losers = listOf(u2, u4)) // u1,u3 beat u2,u4
        fixture(u1 = u1, u2 = u2, date = LocalDate.of(2026, 3, 1)) // scheduled, undecided → not counted

        val records = matches.winLossByUsers(userIds = listOf(u1, u2, u3, u4, u5))

        records.getValue(key = u1) shouldBe WinLossRecord(wins = 2, losses = 1)
        records.getValue(key = u1).total shouldBe 3
        records.getValue(key = u2) shouldBe WinLossRecord(wins = 1, losses = 2)
        records.getValue(key = u3) shouldBe WinLossRecord(wins = 1, losses = 0)
        records.getValue(key = u4) shouldBe WinLossRecord(wins = 0, losses = 1)
        records.containsKey(key = u5) shouldBe false
        matches.winLossByUsers(userIds = emptyList()) shouldBe emptyMap()
    }

    @Test
    fun `winLossByUsers covers a team2 win, one-sided queries, and a user with no team history (#342)`() {
        val a = newUser(uid = "a")
        val b = newUser(uid = "b")
        val c = newUser(uid = "c") // never played — no team membership at all

        // A singles match where TEAM 2 wins (b beats a): exercises the winner-is-team2 branch.
        val match =
            matches.createFixture(
                command =
                    CreateFixtureCommand(
                        matchFormat = TeamType.SINGLES,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = LocalDate.of(2026, 1, 1),
                        team1UserIds = listOf(element = a),
                        team2UserIds = listOf(element = b),
                        team1Name = "T1",
                        team2Name = "T2",
                        createdBy = a,
                    ),
            )
        matches.addResult(
            matchId = match.id,
            sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 2, team2Games = 6, winnerTeamId = match.team2.teamId)),
            winnerTeamId = match.team2.teamId,
            recordedBy = a,
            completedAt = LocalDateTime.now(),
        )

        // Query only the winner: the loser's team has no queried user, so the loser-side lookup is empty.
        matches.winLossByUsers(userIds = listOf(element = b)).let { r ->
            r.keys shouldBe setOf(element = b)
            r.getValue(key = b) shouldBe WinLossRecord(wins = 1, losses = 0)
        }
        // Query only the loser: the winner's team has no queried user, so the winner-side lookup is empty.
        matches.winLossByUsers(userIds = listOf(element = a)).let { r ->
            r.keys shouldBe setOf(element = a)
            r.getValue(key = a) shouldBe WinLossRecord(wins = 0, losses = 1)
        }
        // A user who never joined any team → no memberships → empty result.
        matches.winLossByUsers(userIds = listOf(element = c)) shouldBe emptyMap()
    }

    @Test
    fun `pending-calculation orders by event end date, interleaves eventless by match date, honors override (#335)`() {
        val u1 = newUser(uid = "u1")
        val u2 = newUser(uid = "u2")
        val eventA = event(creator = u1, endDate = LocalDate.of(2026, 1, 10), members = listOf(u1, u2))
        val eventB = event(creator = u1, endDate = LocalDate.of(2026, 1, 20), members = listOf(u1, u2))
        val day = LocalDate.of(2026, 1, 8)
        // Three same-day matches in event A: a2 is dragged to the front (calc_sequence); a1 and a3 are
        // un-dragged (null calc_sequence) and share a completion time, so they tie down to the id.
        val a2 = completedMatch(u1 = u1, u2 = u2, matchDate = day, eventId = eventA)
        matches.reorderCalcSequence(matchIds = listOf(element = a2))
        val tie = LocalDateTime.of(2026, 1, 8, 12, 0)
        val a1 = completedMatch(u1 = u1, u2 = u2, matchDate = day, eventId = eventA, completedAt = tie)
        val a3 = completedMatch(u1 = u1, u2 = u2, matchDate = day, eventId = eventA, completedAt = tie)
        val b1 = completedMatch(u1 = u1, u2 = u2, matchDate = LocalDate.of(2026, 1, 18), eventId = eventB)
        // An eventless match played between the two events' end dates.
        val open = completedMatch(u1 = u1, u2 = u2, matchDate = LocalDate.of(2026, 1, 15))
        // Evented matches queue only once their event is finalized (#403); finalize both so this ordering
        // test sees the full set (the eventless match queues immediately regardless).
        events.finalize(id = eventA, finalizedAt = LocalDateTime.now(), finalizedBy = u1)
        events.finalize(id = eventB, finalizedAt = LocalDateTime.now(), finalizedBy = u1)

        // Default: event A (ends 1/10; a2 dragged first, then the a1/a3 tie) → eventless (1/15) → event B.
        val order = matches.listPendingCalculation().map { it.id }
        order.first() shouldBe a2
        order.subList(fromIndex = 1, toIndex = 3).toSet() shouldBe setOf(a1, a3)
        order.subList(fromIndex = 3, toIndex = 5) shouldBe listOf(open, b1)

        // Admin bumps event B ahead of everything by giving it the lowest processing key.
        events.setCalcPriority(id = eventB, priority = 0.0)
        val overridden = matches.listPendingCalculation().map { it.id }
        overridden.first() shouldBe b1
        overridden[1] shouldBe a2
        overridden.subList(fromIndex = 2, toIndex = 4).toSet() shouldBe setOf(a1, a3)
        overridden.last() shouldBe open
    }

    @Test
    fun `pending-calculation scoped to an event shows that event's fixtures from any creator (#335)`() {
        val host = newUser(uid = "host")
        val otherHost = newUser(uid = "otherHost")
        val p1 = newUser(uid = "p1")
        val p2 = newUser(uid = "p2")
        val target = event(creator = host, endDate = LocalDate.of(2026, 2, 10), members = listOf(host, otherHost, p1, p2))
        val other = event(creator = host, endDate = LocalDate.of(2026, 2, 20), members = listOf(host, p1, p2))
        // Two fixtures in the target event, recorded by different creators — both must appear.
        val byHost = completedMatch(u1 = host, u2 = p1, matchDate = LocalDate.of(2026, 2, 8), eventId = target)
        val byOther =
            completedMatchCreatedBy(creator = otherHost, u1 = otherHost, u2 = p2, matchDate = LocalDate.of(2026, 2, 8), eventId = target)
        // A fixture in a different event and an eventless one — both must be excluded by the event scope.
        completedMatch(u1 = host, u2 = p2, matchDate = LocalDate.of(2026, 2, 18), eventId = other)
        completedMatch(u1 = host, u2 = p1, matchDate = LocalDate.of(2026, 2, 15))

        matches.listPendingCalculation(eventId = target).map { it.id }.toSet() shouldBe setOf(byHost, byOther)
    }

    @Test
    fun `pending-calculation scoped to a creator shows only that host's fixtures (#335)`() {
        val host = newUser(uid = "host")
        val otherHost = newUser(uid = "otherHost")
        val p1 = newUser(uid = "p1")
        val p2 = newUser(uid = "p2")
        val mine = completedMatch(u1 = host, u2 = p1, matchDate = LocalDate.of(2026, 3, 1))
        // A fixture created by another host must be excluded from a creator-scoped list.
        completedMatchCreatedBy(creator = otherHost, u1 = otherHost, u2 = p2, matchDate = LocalDate.of(2026, 3, 1))

        matches.listPendingCalculation(createdBy = host).map { it.id } shouldBe listOf(element = mine)
    }

    @Test
    fun `reorderCalcSequence on an empty list is a harmless no-op (#331)`() {
        // The empty-input path runs no updates and must not throw.
        matches.reorderCalcSequence(matchIds = emptyList())

        val u1 = newUser(uid = "u1")
        val u2 = newUser(uid = "u2")
        val only = completedMatch(u1 = u1, u2 = u2, matchDate = LocalDate.of(2026, 1, 1))
        matches.listPendingCalculation(createdBy = u1).map { it.id } shouldBe listOf(element = only)
    }

    @Test
    fun `pending-calculation excludes an evented match until its event is finalized, always includes eventless (#403)`() {
        val u1 = newUser(uid = "u1")
        val u2 = newUser(uid = "u2")
        val eventId = event(creator = u1, endDate = LocalDate.of(2026, 1, 3), members = listOf(u1, u2))
        val evented = completedMatch(u1 = u1, u2 = u2, matchDate = LocalDate.of(2026, 1, 1), eventId = eventId)
        val eventless = completedMatch(u1 = u1, u2 = u2, matchDate = LocalDate.of(2026, 1, 2))

        // While the event is open, only the event-less match is eligible; the evented one is held back.
        matches.listPendingCalculation().map { it.id }.let { queued ->
            queued shouldContain eventless
            queued shouldNotContain evented
        }
        matches.listPendingCalculation(createdBy = u1).map { it.id }.let { queued ->
            queued shouldContain eventless
            queued shouldNotContain evented
        }

        // Once the event is finalized, its match becomes eligible too.
        events.finalize(id = eventId, finalizedAt = LocalDateTime.now(), finalizedBy = u1)
        matches.listPendingCalculation().map { it.id }.let { queued ->
            queued shouldContain eventless
            queued shouldContain evented
        }
        matches.listPendingCalculation(createdBy = u1).map { it.id } shouldContain evented
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
    fun `setDesignatedPoints on an unknown match is not found (#466)`() {
        matches
            .setDesignatedPoints(matchId = UUID.randomUUID(), designatedPoints = 20)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `setHandicaps on an unknown match is not found (#486)`() {
        matches
            .setHandicaps(
                matchId = UUID.randomUUID(),
                team1Handicap = java.math.BigDecimal("0.300"),
                team2Handicap = null,
            )
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `setDesignatedPoints sets then clears a fixture designation (#466)`() {
        val match = fixture(u1 = newUser(uid = "d1"), u2 = newUser(uid = "d2"))

        matches.setDesignatedPoints(matchId = match.id, designatedPoints = 25).shouldBeRight().designatedPoints shouldBe 25
        matches.setDesignatedPoints(matchId = match.id, designatedPoints = null).shouldBeRight().designatedPoints.shouldBeNull()
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
    fun `windowed match rows carry each completed match's date and weight class (#459)`() {
        val asOf = LocalDateTime.of(2026, 6, 1, 12, 0)
        val today = asOf.toLocalDate()
        val player = newUser(uid = "wc-p")
        val opponent = newUser(uid = "wc-o")

        // A mix of classes on distinct dates, all in-window: two tournament playoffs → tournament; a
        // league round → league; open play → open play. (TOURNAMENT_INITIAL_ROUND's name is unit-tested
        // in RatingConfidenceTest; here it would exceed the pre-existing match_type VARCHAR(20).)
        completedMatch(u1 = player, u2 = opponent, matchDate = today, matchType = MatchType.TOURNAMENT_PLAYOFFS)
        completedMatch(u1 = player, u2 = opponent, matchDate = today.minusDays(3), matchType = MatchType.TOURNAMENT_PLAYOFFS)
        completedMatch(u1 = player, u2 = opponent, matchDate = today.minusDays(7), matchType = MatchType.LEAGUE_PLAY)
        completedMatch(u1 = player, u2 = opponent, matchDate = today.minusDays(10), matchType = MatchType.OPEN_PLAY)

        matches.windowedMatchesInWindow(userId = player, asOf = asOf) shouldContainExactlyInAnyOrder
            listOf(
                WindowMatch(matchDate = today, weightClass = WeightClass.TOURNAMENT),
                WindowMatch(matchDate = today.minusDays(3), weightClass = WeightClass.TOURNAMENT),
                WindowMatch(matchDate = today.minusDays(7), weightClass = WeightClass.LEAGUE),
                WindowMatch(matchDate = today.minusDays(10), weightClass = WeightClass.OPEN_PLAY),
            )
    }

    @Test
    fun `windowed match rows only include completed, participant matches inside the window (#459)`() {
        val asOf = LocalDateTime.of(2026, 6, 1, 12, 0)
        val today = asOf.toLocalDate()
        val player = newUser(uid = "win-p")
        val opponent = newUser(uid = "win-o")
        val other = newUser(uid = "win-x")

        // Just inside the 30-day window (exactly 30 days back) → included.
        completedMatch(u1 = player, u2 = opponent, matchDate = today.minusDays(30), matchType = MatchType.OPEN_PLAY)
        // Just outside (31 days back) → excluded.
        completedMatch(u1 = player, u2 = opponent, matchDate = today.minusDays(31), matchType = MatchType.OPEN_PLAY)
        // A future-dated match → excluded (after asOf).
        completedMatch(u1 = player, u2 = opponent, matchDate = today.plusDays(1), matchType = MatchType.OPEN_PLAY)
        // A scheduled (not completed) fixture → excluded.
        fixture(u1 = player, u2 = opponent, date = today)
        // A match the player didn't take part in → excluded.
        completedMatch(u1 = opponent, u2 = other, matchDate = today, matchType = MatchType.OPEN_PLAY)

        matches.windowedMatchesInWindow(userId = player, asOf = asOf) shouldContainExactlyInAnyOrder
            listOf(element = WindowMatch(matchDate = today.minusDays(30), weightClass = WeightClass.OPEN_PLAY))
        // A player with no qualifying matches is absent from the batched map.
        matches.windowedMatchesInWindow(userIds = listOf(element = newUser(uid = "none")), asOf = asOf).shouldBeEmpty()
    }

    @Test
    fun `windowed match rows include a player on either side, batched in one query (#459)`() {
        val asOf = LocalDateTime.of(2026, 6, 1, 12, 0)
        val today = asOf.toLocalDate()
        val a = newUser(uid = "batch-a")
        val b = newUser(uid = "batch-b")

        // a as team1, b as team2 (open play); then b as team1, a as team2 (tournament) — both sides count.
        completedMatch(u1 = a, u2 = b, matchDate = today, matchType = MatchType.OPEN_PLAY)
        completedMatch(u1 = b, u2 = a, matchDate = today.minusDays(2), matchType = MatchType.TOURNAMENT_PLAYOFFS)

        val rows = matches.windowedMatchesInWindow(userIds = listOf(a, b), asOf = asOf)
        val expected =
            listOf(
                WindowMatch(matchDate = today, weightClass = WeightClass.OPEN_PLAY),
                WindowMatch(matchDate = today.minusDays(2), weightClass = WeightClass.TOURNAMENT),
            )
        rows[a] shouldContainExactlyInAnyOrder expected
        rows[b] shouldContainExactlyInAnyOrder expected
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

    @Test
    fun `completedResultCountByEvents counts recorded results per event, batched, omits the resultless (#483)`() {
        val creator = newUser(uid = "cr")
        val a = newUser(uid = "ca")
        val b = newUser(uid = "cb")
        val withResults = event(creator = creator, endDate = LocalDate.of(2026, 3, 1), members = listOf(a, b))
        val scheduledOnly = event(creator = creator, endDate = LocalDate.of(2026, 4, 1), members = listOf(a, b))

        // Two recorded results in the first event, plus a scheduled (undecided) fixture that must not count.
        completedMatch(u1 = a, u2 = b, matchDate = LocalDate.of(2026, 2, 20), eventId = withResults)
        completedMatch(u1 = b, u2 = a, matchDate = LocalDate.of(2026, 2, 25), eventId = withResults)
        fixture(u1 = a, u2 = b, date = LocalDate.of(2026, 2, 28)).let { f ->
            transaction { MatchesTable.update(where = { MatchesTable.id eq f.id }) { it[eventId] = withResults } }
        }
        // The second event has only a scheduled fixture — no recorded result.
        fixture(u1 = a, u2 = b, date = LocalDate.of(2026, 3, 20)).let { f ->
            transaction { MatchesTable.update(where = { MatchesTable.id eq f.id }) { it[eventId] = scheduledOnly } }
        }

        val counts = matches.completedResultCountByEvents(eventIds = listOf(withResults, scheduledOnly))
        counts[withResults] shouldBe 2
        // An event with no recorded result is absent from the map (callers default it to 0).
        counts[scheduledOnly].shouldBeNull()
    }

    @Test
    fun `completedResultCountByEvents returns an empty map for no ids (#483)`() {
        matches.completedResultCountByEvents(eventIds = emptyList()).shouldBeEmpty()
    }

    @Test
    fun `latestRatedMatchDatesByUsers returns an empty map for no ids (#478)`() {
        matches.latestRatedMatchDatesByUsers(userIds = emptyList()).shouldBeEmpty()
    }

    @Test
    fun `latestRatedMatchDatesByUsers is empty for a user with no team memberships (#478)`() {
        val loner = newUser(uid = "loner")
        matches.latestRatedMatchDatesByUsers(userIds = listOf(element = loner)).shouldBeEmpty()
    }
}
