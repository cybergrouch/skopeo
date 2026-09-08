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
import org.skopeo.common.error.ServiceError
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.MatchStatus
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.ScoreEvent
import org.skopeo.domain.model.TeamSide
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.LiveMatchRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.fixtureEventId
import java.time.LocalDate
import java.util.UUID

/**
 * Live scoring end to end against a real database (#911 step 3b).
 *
 * Two things here are not obvious from the engine tests and are the reason this suite exists: the
 * capability gate is **flat** (any SCORER, any match — the one event-scoped operation outside the #789
 * club rule), and the claim is **soft** (takeover is the expected path, not an error).
 */
class LiveMatchServiceTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val service = LiveMatchService()
    private val live = LiveMatchRepository()
    private val matches = MatchRepository()

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

    private fun umpire(uid: String = "ump"): UUID = user(uid = uid, roles = setOf(Capability.PLAYER, Capability.SCORER))

    private fun fixture(): UUID {
        val home = user(uid = "home")
        val away = user(uid = "away")
        return matches
            .createFixture(
                command =
                    CreateFixtureCommand(
                        matchFormat = TeamType.SINGLES,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = LocalDate.now(),
                        team1UserIds = listOf(element = home),
                        team2UserIds = listOf(element = away),
                        team1Name = "home",
                        team2Name = "away",
                        createdBy = home,
                        eventId = fixtureEventId(home, away),
                    ),
            ).toDomain()
            .id
    }

    private fun point(side: TeamSide) = ScoreEvent.PointWon(side = side)

    @Test
    fun `a plain player cannot score`() {
        user(uid = "nobody")
        val matchId = fixture()
        service
            .record(token = token(uid = "nobody"), matchId = matchId, event = point(side = TeamSide.TEAM1))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `an unknown caller cannot score`() {
        val matchId = fixture()
        service
            .record(token = token(uid = "ghost"), matchId = matchId, event = point(side = TeamSide.TEAM1))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `a SCORER may score a match with no connection to their club`() {
        // The deliberate exception to #789: the gate is a flat capability check, because an umpire pool
        // moves between clubs. Nothing about this match involves the umpire.
        umpire()
        val matchId = fixture()
        service
            .record(token = token(uid = "ump"), matchId = matchId, event = point(side = TeamSide.TEAM1))
            .shouldBeRight()
            .state
            .displayPoints(side = TeamSide.TEAM1) shouldBe "15"
    }

    @Test
    fun `a HOST may score without holding SCORER, because SCORING_ROLES composes match management`() {
        user(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val matchId = fixture()
        service
            .record(token = token(uid = "host"), matchId = matchId, event = point(side = TeamSide.TEAM1))
            .shouldBeRight()
    }

    @Test
    fun `claiming moves a scheduled fixture to IN_PROGRESS`() {
        // MatchStatus.IN_PROGRESS has existed in the enum since the beginning with nothing writing it;
        // a live match is its first user.
        val id = umpire()
        val matchId = fixture()
        matches.findById(matchId = matchId).shouldBeRight().toDomain().status shouldBe MatchStatus.SCHEDULED

        service.claim(token = token(uid = "ump"), matchId = matchId).shouldBeRight().scorerId shouldBe id
        matches.findById(matchId = matchId).shouldBeRight().toDomain().status shouldBe MatchStatus.IN_PROGRESS
    }

    @Test
    fun `a second umpire takes the claim over rather than being refused`() {
        umpire(uid = "first")
        val second = umpire(uid = "second")
        val matchId = fixture()

        service.claim(token = token(uid = "first"), matchId = matchId).shouldBeRight()
        service.claim(token = token(uid = "second"), matchId = matchId).shouldBeRight().scorerId shouldBe second
    }

    @Test
    fun `releasing the claim leaves the fixture in progress, because the match is still being played`() {
        umpire()
        val matchId = fixture()
        service.claim(token = token(uid = "ump"), matchId = matchId).shouldBeRight()

        service.release(token = token(uid = "ump"), matchId = matchId).shouldBeRight().scorerId shouldBe null
        matches.findById(matchId = matchId).shouldBeRight().toDomain().status shouldBe MatchStatus.IN_PROGRESS
    }

    @Test
    fun `recorded events accumulate into a score and a sequence`() {
        umpire()
        val matchId = fixture()

        repeat(times = 4) {
            service.record(token = token(uid = "ump"), matchId = matchId, event = point(side = TeamSide.TEAM1))
        }
        val view = service.view(matchId = matchId)
        view.state.gamesTeam1 shouldBe 1
        view.sequence shouldBe 4L
    }

    @Test
    fun `undo appends a marker and takes the score back`() {
        umpire()
        val matchId = fixture()
        service.record(token = token(uid = "ump"), matchId = matchId, event = point(side = TeamSide.TEAM1))
        service.record(token = token(uid = "ump"), matchId = matchId, event = point(side = TeamSide.TEAM1))

        val undone = service.undo(token = token(uid = "ump"), matchId = matchId).shouldBeRight()
        undone.state.displayPoints(side = TeamSide.TEAM1) shouldBe "15"
        // Three rows: two points and the marker. Nothing was deleted.
        live.log(matchId = matchId).shouldHaveSize(size = 3)
    }

    @Test
    fun `undo twice walks back two actions`() {
        umpire()
        val matchId = fixture()
        repeat(times = 3) {
            service.record(token = token(uid = "ump"), matchId = matchId, event = point(side = TeamSide.TEAM1))
        }
        service.undo(token = token(uid = "ump"), matchId = matchId).shouldBeRight()
        val twice = service.undo(token = token(uid = "ump"), matchId = matchId).shouldBeRight()

        // The second undo must target the second point, not the marker the first undo wrote.
        twice.state.displayPoints(side = TeamSide.TEAM1) shouldBe "15"
        live.log(matchId = matchId).shouldHaveSize(size = 5)
    }

    @Test
    fun `undo with nothing to undo is not an error and writes nothing`() {
        umpire()
        val matchId = fixture()

        service.undo(token = token(uid = "ump"), matchId = matchId).shouldBeRight().sequence shouldBe 0L
        live.log(matchId = matchId).shouldHaveSize(size = 0)
    }

    @Test
    fun `undoing everything and scoring again continues from the surviving log`() {
        umpire()
        val matchId = fixture()
        service.record(token = token(uid = "ump"), matchId = matchId, event = point(side = TeamSide.TEAM1))
        service.undo(token = token(uid = "ump"), matchId = matchId).shouldBeRight()

        val after =
            service
                .record(token = token(uid = "ump"), matchId = matchId, event = point(side = TeamSide.TEAM2))
                .shouldBeRight()
        after.state.displayPoints(side = TeamSide.TEAM1) shouldBe "0"
        after.state.displayPoints(side = TeamSide.TEAM2) shouldBe "15"
    }

    @Test
    fun `start, pause and resume round-trip through the database`() {
        umpire()
        val matchId = fixture()

        service.record(token = token(uid = "ump"), matchId = matchId, event = ScoreEvent.MatchStarted).shouldBeRight()
        service.record(token = token(uid = "ump"), matchId = matchId, event = point(side = TeamSide.TEAM1))
        val paused =
            service
                .record(token = token(uid = "ump"), matchId = matchId, event = ScoreEvent.Paused)
                .shouldBeRight()
        paused.state.isPaused shouldBe true
        paused.state.hasStarted shouldBe true

        val resumed =
            service
                .record(token = token(uid = "ump"), matchId = matchId, event = ScoreEvent.Resumed)
                .shouldBeRight()
        resumed.state.isPaused shouldBe false
        // The score is untouched by the suspension.
        resumed.state.displayPoints(side = TeamSide.TEAM1) shouldBe "15"
    }

    @Test
    fun `a paused match keeps its log indefinitely, since a rain delay has no deadline`() {
        // The reason there is no staleness sweep: a suspended match may resume days later on a court the
        // host cannot yet book, so no timeout could tell "abandoned" from "waiting for weather".
        umpire()
        val matchId = fixture()
        service.record(token = token(uid = "ump"), matchId = matchId, event = ScoreEvent.MatchStarted)
        service.record(token = token(uid = "ump"), matchId = matchId, event = ScoreEvent.Paused)

        live.log(matchId = matchId).shouldHaveSize(size = 2)
        service.view(matchId = matchId).state.isPaused shouldBe true
    }

    @Test
    fun `every recorded action is timestamped, so durations are derivable`() {
        umpire()
        val matchId = fixture()
        service.record(token = token(uid = "ump"), matchId = matchId, event = ScoreEvent.MatchStarted)
        service.record(token = token(uid = "ump"), matchId = matchId, event = point(side = TeamSide.TEAM1))

        val rows = live.log(matchId = matchId)
        rows.shouldHaveSize(size = 2)
        // Non-decreasing, and every row carries one — the anchor a match-duration figure is measured from.
        (rows[1].recordedAt >= rows[0].recordedAt) shouldBe true
    }

    @Test
    fun `the recording umpire is attributed on every row`() {
        val id = umpire()
        val matchId = fixture()
        service.record(token = token(uid = "ump"), matchId = matchId, event = point(side = TeamSide.TEAM1))
        service.undo(token = token(uid = "ump"), matchId = matchId)

        live.log(matchId = matchId).map { it.recordedBy }.toSet() shouldBe setOf(element = id)
    }

    @Test
    fun `a disabled match cannot be scored`() {
        umpire()
        val matchId = fixture()
        matches.setActive(matchId = matchId, active = false, disabledAt = java.time.LocalDateTime.now())

        service
            .record(token = token(uid = "ump"), matchId = matchId, event = point(side = TeamSide.TEAM1))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `a rated match cannot be scored, because its result already fed ratings and points`() {
        val id = umpire()
        val matchId = fixture()
        matches.markRated(matchId = matchId, ratedAt = java.time.LocalDateTime.now(), ratedBy = id)

        service
            .record(token = token(uid = "ump"), matchId = matchId, event = point(side = TeamSide.TEAM1))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `losing the sequence race three times is reported rather than retried forever`() {
        // Sustained contention, faked by a repository whose append never wins. The real path retries
        // because the winning event may have changed what the umpire's action means — but spinning
        // courtside would be worse than saying so, hence a bounded attempt count.
        umpire()
        val matchId = fixture()
        val alwaysLoses =
            object : LiveMatchRepository() {
                var attempts = 0

                override fun append(
                    matchId: UUID,
                    sequence: Long,
                    kind: String,
                    side: String?,
                    playerId: UUID?,
                    targetSequence: Long?,
                    recordedBy: UUID,
                    recordedAt: java.time.LocalDateTime,
                ): Boolean {
                    attempts += 1
                    return false
                }
            }

        LiveMatchService(live = alwaysLoses)
            .record(token = token(uid = "ump"), matchId = matchId, event = point(side = TeamSide.TEAM1))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()

        // Bounded, and it really did try more than once.
        alwaysLoses.attempts shouldBe 3
    }

    @Test
    fun `an unknown match is not found`() {
        umpire()
        service
            .record(token = token(uid = "ump"), matchId = UUID.randomUUID(), event = point(side = TeamSide.TEAM1))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }
}
