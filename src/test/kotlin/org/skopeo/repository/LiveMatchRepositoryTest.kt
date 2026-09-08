// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.redaction.asRedactable
import org.skopeo.domain.mapper.entity.livematch.LiveMatchEventKinds
import org.skopeo.domain.mapper.entity.livematch.kindOf
import org.skopeo.domain.mapper.entity.livematch.sideOf
import org.skopeo.domain.mapper.entity.livematch.toLoggedAction
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.LoggedAction
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.ScoreEvent
import org.skopeo.domain.model.TeamSide
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.livematch.ScoreEngine
import org.skopeo.repository.persistence.MatchUmpireEntity
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.fixtureEventId
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The live-scoring log against a real database (#911 step 3).
 *
 * The centrepiece is [concurrent umpire writes cannot both take the same sequence] — #911 requires that
 * be enforced by a **database constraint, not application ordering**, so it is tested by actually racing
 * two threads rather than by asserting that some Kotlin code looks careful.
 */
class LiveMatchRepositoryTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val repository = LiveMatchRepository()
    private val matches = MatchRepository()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun user(uid: String): UUID =
        UserRepository()
            .provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid.asRedactable(),
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    ),
            ).toDomain()
            .id

    /** A fixture to hang a log off. The log's content is what is under test, not the match. */
    private fun fixture(
        one: String = "home",
        two: String = "away",
    ): UUID {
        val home = user(uid = one)
        val away = user(uid = two)
        return matches
            .createFixture(
                command =
                    CreateFixtureCommand(
                        matchFormat = TeamType.SINGLES,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = LocalDate.now(),
                        team1UserIds = listOf(element = home),
                        team2UserIds = listOf(element = away),
                        team1Name = one,
                        team2Name = two,
                        createdBy = home,
                        eventId = fixtureEventId(home, away),
                    ),
            ).toDomain()
            .id
    }

    @Test
    fun `an appended log reads back in sequence order and folds to a score`() {
        val matchId = fixture()
        val umpire = user(uid = "umpire")

        (1..4).forEach { n ->
            repository.append(
                matchId = matchId,
                sequence = n.toLong(),
                kind = LiveMatchEventKinds.POINT_WON,
                side = TeamSide.TEAM1.name,
                recordedBy = umpire,
            ) shouldBe true
        }

        val log = repository.log(matchId = matchId).map { it.toLoggedAction() }
        log.shouldHaveSize(size = 4)
        log.map { it.sequence } shouldBe listOf(1L, 2L, 3L, 4L)
        ScoreEngine.replay(log = log).gamesTeam1 shouldBe 1
    }

    @Test
    fun `every event kind round-trips through the database`() {
        // The mapper and chk_live_match_events_payload have to agree about which columns each kind uses.
        // A kind that disagrees is rejected by the CHECK, so this is the test that keeps them in step.
        val matchId = fixture()
        val umpire = user(uid = "umpire")
        val server = user(uid = "server")

        val events: List<ScoreEvent> =
            listOf(
                ScoreEvent.PointWon(side = TeamSide.TEAM1),
                ScoreEvent.GameAwarded(side = TeamSide.TEAM2),
                ScoreEvent.TiebreakStarted,
                ScoreEvent.SetAwarded(side = TeamSide.TEAM1),
                ScoreEvent.ServerAssigned(playerId = server),
                ScoreEvent.Retired(side = TeamSide.TEAM2),
                ScoreEvent.Defaulted(side = TeamSide.TEAM1),
                ScoreEvent.MatchAwarded(side = TeamSide.TEAM1),
            )
        events.forEachIndexed { index, event ->
            repository.append(
                matchId = matchId,
                sequence = index + 1L,
                kind = kindOf(event = event),
                side = sideOf(event = event),
                playerId = (event as? ScoreEvent.ServerAssigned)?.playerId,
                recordedBy = umpire,
            ) shouldBe true
        }

        val readBack = repository.log(matchId = matchId).map { it.toLoggedAction() }
        readBack.filterIsInstance<LoggedAction.Scored>().map { it.event } shouldBe events
    }

    @Test
    fun `an undo marker round-trips and cancels its target`() {
        val matchId = fixture()
        val umpire = user(uid = "umpire")

        repository.append(
            matchId = matchId,
            sequence = 1,
            kind = LiveMatchEventKinds.POINT_WON,
            side = TeamSide.TEAM1.name,
            recordedBy = umpire,
        )
        repository.append(
            matchId = matchId,
            sequence = 2,
            kind = LiveMatchEventKinds.UNDONE,
            targetSequence = 1,
            recordedBy = umpire,
        )

        val log = repository.log(matchId = matchId).map { it.toLoggedAction() }
        // Both rows are still there — appending, not popping.
        log.shouldHaveSize(size = 2)
        log[1] shouldBe LoggedAction.Undone(sequence = 2, targetSequence = 1)
        ScoreEngine.replay(log = log).pointsTeam1 shouldBe 0
    }

    @Test
    fun `a second write at the same sequence is refused by the database`() {
        val matchId = fixture()
        val umpire = user(uid = "umpire")

        repository.append(
            matchId = matchId,
            sequence = 1,
            kind = LiveMatchEventKinds.POINT_WON,
            side = TeamSide.TEAM1.name,
            recordedBy = umpire,
        ) shouldBe true

        // Same sequence, different content: the loser is told so rather than overwriting.
        repository.append(
            matchId = matchId,
            sequence = 1,
            kind = LiveMatchEventKinds.POINT_WON,
            side = TeamSide.TEAM2.name,
            recordedBy = umpire,
        ) shouldBe false

        repository.log(matchId = matchId).shouldHaveSize(size = 1)
        repository.log(matchId = matchId).single().side shouldBe TeamSide.TEAM1.name
    }

    @Test
    fun `two matches number their sequences independently`() {
        val first = fixture()
        val umpire = user(uid = "umpire")
        val second = fixture(one = "p3", two = "p4")

        // The constraint is per match, so sequence 1 must be free on the second one.
        repository.append(
            matchId = first,
            sequence = 1,
            kind = LiveMatchEventKinds.POINT_WON,
            side = TeamSide.TEAM1.name,
            recordedBy = umpire,
        ) shouldBe true
        repository.append(
            matchId = second,
            sequence = 1,
            kind = LiveMatchEventKinds.POINT_WON,
            side = TeamSide.TEAM1.name,
            recordedBy = umpire,
        ) shouldBe true
    }

    @Test
    fun `concurrent umpire writes cannot both take the same sequence`() {
        // #911 requires this be enforced by a DATABASE CONSTRAINT rather than application ordering, which
        // is why the test races real threads instead of asserting that some code looks careful. Two Cloud
        // Run instances reading the same log will compute the same "next" sequence; exactly one insert may
        // survive, and the other must be told it lost so it can retry against what actually landed.
        val matchId = fixture()
        val umpire = user(uid = "umpire")
        val contenders = 8
        val pool = Executors.newFixedThreadPool(contenders)

        val results =
            try {
                pool
                    .invokeAll(
                        (1..contenders).map { n ->
                            Callable {
                                repository.append(
                                    matchId = matchId,
                                    sequence = 1,
                                    kind = LiveMatchEventKinds.POINT_WON,
                                    side = if (n % 2 == 0) TeamSide.TEAM1.name else TeamSide.TEAM2.name,
                                    recordedBy = umpire,
                                )
                            }
                        },
                    ).map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdown()
            }

        results.count { it } shouldBe 1
        repository.log(matchId = matchId).shouldHaveSize(size = 1)
    }

    @Test
    fun `lastSequence reports zero for an untouched match and the high-water mark otherwise`() {
        val matchId = fixture()
        val umpire = user(uid = "umpire")
        repository.lastSequence(matchId = matchId) shouldBe 0L

        listOf(1L, 2L, 3L).forEach { n ->
            repository.append(
                matchId = matchId,
                sequence = n,
                kind = LiveMatchEventKinds.POINT_WON,
                side = TeamSide.TEAM1.name,
                recordedBy = umpire,
            )
        }
        repository.lastSequence(matchId = matchId) shouldBe 3L
    }

    @Test
    fun `claiming a match displaces the previous scorer, because taking over is expected`() {
        val matchId = fixture()
        val first = user(uid = "first")
        val second = user(uid = "second")

        repository.scorer(matchId = matchId) shouldBe null

        repository.claim(matchId = matchId, scorerId = first)
        repository.scorer(matchId = matchId)?.scorerId shouldBe first

        // A soft claim: the second umpire takes over rather than being refused.
        repository.claim(matchId = matchId, scorerId = second)
        repository.scorer(matchId = matchId)?.scorerId shouldBe second

        repository.releaseClaim(matchId = matchId)
        repository.scorer(matchId = matchId) shouldBe null
        // Releasing twice is not an error.
        repository.releaseClaim(matchId = matchId)
    }

    @Test
    fun `umpire credit survives discarding the log`() {
        // The point of match_umpires: §8a makes the log disposable, so attribution kept only on the log
        // would vanish exactly when the match becomes historical.
        val matchId = fixture()
        val umpire = user(uid = "umpire")
        val now = LocalDateTime.now()

        repository.append(
            matchId = matchId,
            sequence = 1,
            kind = LiveMatchEventKinds.POINT_WON,
            side = TeamSide.TEAM1.name,
            recordedBy = umpire,
        )
        repository.recordUmpires(
            matchId = matchId,
            umpires =
                listOf(
                    element =
                        MatchUmpireEntity(
                            userId = umpire,
                            eventsRecorded = 1,
                            firstRecordedAt = now,
                            lastRecordedAt = now,
                        ),
                ),
        )

        repository.discardLog(matchId = matchId)

        repository.log(matchId = matchId).shouldHaveSize(size = 0)
        repository.umpires(matchId = matchId).single().userId shouldBe umpire
    }

    @Test
    fun `recording umpires again restates the credit rather than doubling it`() {
        val matchId = fixture()
        val umpire = user(uid = "umpire")
        val now = LocalDateTime.now()
        val credit =
            MatchUmpireEntity(userId = umpire, eventsRecorded = 5, firstRecordedAt = now, lastRecordedAt = now)

        repository.recordUmpires(matchId = matchId, umpires = listOf(element = credit))
        repository.recordUmpires(matchId = matchId, umpires = listOf(element = credit.copy(eventsRecorded = 9)))

        repository.umpires(matchId = matchId).shouldHaveSize(size = 1)
        repository.umpires(matchId = matchId).single().eventsRecorded shouldBe 9
    }

    @Test
    fun `two umpires who shared a match are both credited, most active first`() {
        val matchId = fixture()
        val busy = user(uid = "busy")
        val brief = user(uid = "brief")
        val now = LocalDateTime.now()

        repository.recordUmpires(
            matchId = matchId,
            umpires =
                listOf(
                    MatchUmpireEntity(userId = brief, eventsRecorded = 2, firstRecordedAt = now, lastRecordedAt = now),
                    MatchUmpireEntity(userId = busy, eventsRecorded = 40, firstRecordedAt = now, lastRecordedAt = now),
                ),
        )

        repository.umpires(matchId = matchId).map { it.userId } shouldBe listOf(busy, brief)
    }
}
