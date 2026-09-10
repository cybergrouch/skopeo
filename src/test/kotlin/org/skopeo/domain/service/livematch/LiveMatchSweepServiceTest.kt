// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.dto.livematch.LiveMatchSweepRequest
import org.skopeo.common.error.ServiceError
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.livematch.LiveMatchEventKinds
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.MatchStatus
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.TeamSide
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.LiveMatchRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import org.skopeo.repository.persistence.MatchUmpireEntity
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.fixtureEventId
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The retention sweep (#939).
 *
 * This is the only thing in the system that **deletes**, so the tests are mostly about what it must
 * refuse to touch — a sweep that is merely correct on the happy path is not good enough when the
 * failure mode is losing a live match mid-play.
 */
class LiveMatchSweepServiceTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val live = LiveMatchRepository()
    private val matches = MatchRepository()
    private val now: LocalDateTime = LocalDateTime.of(2026, 6, 1, 12, 0)
    private val service = LiveMatchSweepService(clock = { now })

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun user(
        uid: String,
        roles: Set<Capability> = setOf(element = Capability.PLAYER),
    ): UUID =
        UserRepository()
            .provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid.asRedactable(),
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                        capabilities = roles,
                    ),
            ).toDomain()
            .id

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid.asRedactable())

    private fun admin(): String {
        user(uid = "boss", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        return "boss"
    }

    /** A match with [rows] log entries recorded [ageDays] ago, in [status]. */
    private fun scoredMatch(
        name: String,
        ageDays: Long,
        status: MatchStatus,
        rows: Int = 3,
    ): UUID {
        val a = user(uid = "$name-a")
        val b = user(uid = "$name-b")
        val id =
            matches
                .createFixture(
                    command =
                        CreateFixtureCommand(
                            matchFormat = TeamType.SINGLES,
                            matchType = MatchType.OPEN_PLAY,
                            matchDate = LocalDate.of(2026, 1, 1),
                            team1UserIds = listOf(element = a),
                            team2UserIds = listOf(element = b),
                            team1Name = "$name-a",
                            team2Name = "$name-b",
                            createdBy = a,
                            eventId = fixtureEventId(a, b),
                        ),
                ).toDomain()
                .id
        repeat(times = rows) { n ->
            live.append(
                matchId = id,
                sequence = n + 1L,
                kind = LiveMatchEventKinds.POINT_WON,
                side = TeamSide.TEAM1.name,
                recordedBy = a,
                recordedAt = now.minusDays(ageDays),
            )
        }
        matches.setStatus(matchId = id, status = status.name)
        return id
    }

    @Test
    fun `an old finished match is pruned`() {
        val boss = admin()
        val id = scoredMatch(name = "old", ageDays = 200, status = MatchStatus.COMPLETED)

        val result =
            service.sweep(token = token(uid = boss), request = LiveMatchSweepRequest(dryRun = false)).shouldBeRight()

        result.prunedMatches shouldBe 1
        result.prunedRows shouldBe 3
        live.log(matchId = id).shouldHaveSize(size = 0)
    }

    @Test
    fun `a match still IN_PROGRESS is never pruned, however old its log (#930)`() {
        // THE guard. A match suspended for weather may resume days — or weeks — later, and sweeping it
        // would delete a live scoring session mid-match. Age is never sufficient on its own.
        val boss = admin()
        val id = scoredMatch(name = "paused", ageDays = 500, status = MatchStatus.IN_PROGRESS)

        service.sweep(token = token(uid = boss), request = LiveMatchSweepRequest(dryRun = false)).shouldBeRight()

        live.log(matchId = id).shouldHaveSize(size = 3)
    }

    @Test
    fun `a scheduled match is never pruned either`() {
        val boss = admin()
        val id = scoredMatch(name = "sched", ageDays = 500, status = MatchStatus.SCHEDULED)

        service.sweep(token = token(uid = boss), request = LiveMatchSweepRequest(dryRun = false)).shouldBeRight()

        live.log(matchId = id).shouldHaveSize(size = 3)
    }

    @Test
    fun `a recently finished match is kept, so a dispute can still be inspected`() {
        val boss = admin()
        val id = scoredMatch(name = "recent", ageDays = 5, status = MatchStatus.COMPLETED)

        val result =
            service.sweep(token = token(uid = boss), request = LiveMatchSweepRequest(dryRun = false)).shouldBeRight()

        result.prunedMatches shouldBe 0
        live.log(matchId = id).shouldHaveSize(size = 3)
    }

    @Test
    fun `a dry run reports what it would do and deletes nothing`() {
        // The default, and the reason it is the default: this is the only thing in the system that
        // removes rows, so an absent or malformed body must not.
        val boss = admin()
        val id = scoredMatch(name = "dry", ageDays = 200, status = MatchStatus.COMPLETED)

        val result = service.sweep(token = token(uid = boss), request = LiveMatchSweepRequest()).shouldBeRight()

        result.dryRun shouldBe true
        result.prunedMatches shouldBe 1
        result.prunedRows shouldBe 0
        live.log(matchId = id).shouldHaveSize(size = 3)
    }

    @Test
    fun `the umpire credit survives the prune, which is why disposal is safe (#929)`() {
        val boss = admin()
        val id = scoredMatch(name = "credit", ageDays = 200, status = MatchStatus.COMPLETED)
        val umpire = user(uid = "ump")
        live.recordUmpires(
            matchId = id,
            umpires =
                listOf(
                    element =
                        MatchUmpireEntity(
                            userId = umpire,
                            eventsRecorded = 3,
                            firstRecordedAt = now.minusDays(200),
                            lastRecordedAt = now.minusDays(200),
                        ),
                ),
        )

        service.sweep(token = token(uid = boss), request = LiveMatchSweepRequest(dryRun = false)).shouldBeRight()

        live.log(matchId = id).shouldHaveSize(size = 0)
        // The whole reason the log can be thrown away: attribution outlives it.
        live.umpires(matchId = id).single().userId shouldBe umpire
    }

    @Test
    fun `the spectator document is discarded alongside the log (#938, #943)`() {
        // Also the tidy-up for documents still carrying fields since removed from the payload: the
        // whole document goes, so there is nothing left to be stale.
        val discarded = mutableListOf<String>()
        val boss = admin()
        val id = scoredMatch(name = "doc", ageDays = 200, status = MatchStatus.COMPLETED)
        val code = matches.findById(matchId = id).shouldBeRight().toDomain().publicCode

        LiveMatchSweepService(
            clock = { now },
            broadcast =
                object : LiveScoreBroadcaster {
                    override fun publish(payload: LiveScorePayload) = Unit

                    override fun discard(publicCode: String) {
                        discarded += publicCode
                    }
                },
        ).sweep(token = token(uid = boss), request = LiveMatchSweepRequest(dryRun = false)).shouldBeRight()

        discarded shouldBe listOf(element = code)
    }

    @Test
    fun `a shorter retention window can be requested, and a longer one keeps more`() {
        val boss = admin()
        scoredMatch(name = "mid", ageDays = 30, status = MatchStatus.COMPLETED)

        service
            .sweep(token = token(uid = boss), request = LiveMatchSweepRequest(retentionDays = 7))
            .shouldBeRight()
            .prunedMatches shouldBe 1
        service
            .sweep(token = token(uid = boss), request = LiveMatchSweepRequest(retentionDays = 365))
            .shouldBeRight()
            .prunedMatches shouldBe 0
    }

    @Test
    fun `a retention window of zero is floored at one day`() {
        // Guards the obvious foot-gun: retentionDays = 0 must not mean "delete everything finished,
        // including a match recorded a minute ago".
        val boss = admin()
        scoredMatch(name = "today", ageDays = 0, status = MatchStatus.COMPLETED)

        val result =
            service.sweep(token = token(uid = boss), request = LiveMatchSweepRequest(retentionDays = 0)).shouldBeRight()

        result.retentionDays shouldBe 1
        result.prunedMatches shouldBe 0
    }

    @Test
    fun `only an administrator may sweep`() {
        user(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        service
            .sweep(token = token(uid = "host"), request = LiveMatchSweepRequest())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .sweep(token = token(uid = "ghost"), request = LiveMatchSweepRequest())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }
}
