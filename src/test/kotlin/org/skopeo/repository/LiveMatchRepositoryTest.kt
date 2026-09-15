// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.domain.mapper.entity.livematch.LiveMatchEventKinds
import org.skopeo.domain.mapper.entity.livematch.toLoggedAction
import org.skopeo.domain.model.LoggedAction
import org.skopeo.domain.model.TeamSide
import org.skopeo.domain.service.livematch.ScoreEngine
import org.skopeo.repository.persistence.MatchUmpireEntity
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.seedLiveMatchFixture
import org.skopeo.testsupport.seedLiveMatchUser
import java.time.LocalDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The live-scoring log against a real database (#911 step 3).
 *
 * The centrepiece is [concurrent umpire writes cannot both take the same sequence] — #911 requires that
 * be enforced by a **database constraint, not application ordering**, so it is tested by actually racing
 * two threads rather than by asserting that some Kotlin code looks careful.
 *
 * What is deliberately *not* here: the per-kind round-trip. This suite used to carry a test called
 * "every event kind round-trips through the database" whose list of kinds was hand-written, and which
 * had drifted to eight of the twelve — it claimed the coverage that let `SET_STARTED` ship half-wired
 * (#988). `LiveMatchEventKindContractTest` replaces it by enumerating the sealed hierarchy instead
 * (#989), which is the only version of that test that can stay true.
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

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    @Test
    fun `an appended log reads back in sequence order and folds to a score`() {
        val matchId = seedLiveMatchFixture()
        val umpire = seedLiveMatchUser(uid = "umpire")

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
    fun `an undo marker round-trips and cancels its target`() {
        val matchId = seedLiveMatchFixture()
        val umpire = seedLiveMatchUser(uid = "umpire")

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
        val matchId = seedLiveMatchFixture()
        val umpire = seedLiveMatchUser(uid = "umpire")

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
    fun `a violation that is NOT a sequence collision throws rather than reporting a lost race (#989)`() {
        // The distinction the old `catch (ExposedSQLException) { false }` erased. A taken sequence is a
        // routine outcome and returns false so the caller retries; anything else is not retryable, and
        // flattening it into the same false is what made a rejected event kind surface as "another
        // scorer is writing" after three pointless attempts (#988).
        val matchId = seedLiveMatchFixture()
        val umpire = seedLiveMatchUser(uid = "umpire")

        shouldThrow<ExposedSQLException> {
            repository.append(
                matchId = matchId,
                sequence = 1,
                kind = "NOT_A_REAL_KIND",
                side = TeamSide.TEAM1.name,
                recordedBy = umpire,
            )
        }

        // And nothing was written, so a caller that retries is not building on a half-applied row.
        repository.log(matchId = matchId).shouldBeEmpty()
    }

    @Test
    fun `two matches number their sequences independently`() {
        val first = seedLiveMatchFixture()
        val umpire = seedLiveMatchUser(uid = "umpire")
        val second = seedLiveMatchFixture(one = "p3", two = "p4")

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
        val matchId = seedLiveMatchFixture()
        val umpire = seedLiveMatchUser(uid = "umpire")
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
        val matchId = seedLiveMatchFixture()
        val umpire = seedLiveMatchUser(uid = "umpire")
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
        val matchId = seedLiveMatchFixture()
        val first = seedLiveMatchUser(uid = "first")
        val second = seedLiveMatchUser(uid = "second")

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
        val matchId = seedLiveMatchFixture()
        val umpire = seedLiveMatchUser(uid = "umpire")
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
        val matchId = seedLiveMatchFixture()
        val umpire = seedLiveMatchUser(uid = "umpire")
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
        val matchId = seedLiveMatchFixture()
        val busy = seedLiveMatchUser(uid = "busy")
        val brief = seedLiveMatchUser(uid = "brief")
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
