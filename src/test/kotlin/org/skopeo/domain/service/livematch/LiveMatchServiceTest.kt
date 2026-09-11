// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.dto.livematch.LiveScoreEventRequest
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
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.fixtureEventId
import java.time.LocalDate
import java.time.LocalDateTime
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

    /**
     * Rows [fixture] seeds before a test scores anything: MATCH_STARTED and SERVER_ASSIGNED.
     *
     * Named rather than folded into each expected count, so a reader can see *why* a log of "two
     * points" holds four rows, and so these assertions move together if the arrangement changes.
     */
    private val setupRows = 2

    private fun umpire(uid: String = "ump"): UUID = user(uid = uid, roles = setOf(Capability.PLAYER, Capability.SCORER))

    /**
     * A fixture that is **under way**: created, started, and with a server assigned.
     *
     * Since #984/#985/#986 the service refuses scoring on a match that has not been started or has
     * nobody serving, so this is what "a match you can score" now means. Tests whose subject IS a gate
     * use [scheduledFixture] and arrange the state themselves.
     */
    private fun fixture(): UUID {
        val home = user(uid = "home")
        val away = user(uid = "away")
        val matchId = createFixture(home = home, away = away)
        beginPlay(matchId = matchId, server = home)
        return matchId
    }

    /** A freshly created fixture, not yet started — the state an umpire first opens. */
    private fun scheduledFixture(): UUID = createFixture(home = user(uid = "home"), away = user(uid = "away"))

    private fun createFixture(
        home: UUID,
        away: UUID,
    ): UUID =
        matches
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

    /**
     * Put [matchId] into a state where points can be recorded: started, with a server.
     *
     * Since #984/#985/#986 the service refuses scoring on a match that has not been started or has
     * nobody serving. Appended straight to the log rather than through `record`, so the arrangement
     * does not depend on the very guards under test and does not consume the sequence numbers a test
     * may be asserting on.
     */
    private fun beginPlay(
        matchId: UUID,
        server: UUID,
    ) {
        listOf(
            LiveMatchEventKinds.MATCH_STARTED to null,
            LiveMatchEventKinds.SERVER_ASSIGNED to server,
        ).forEach { (kind, playerId) ->
            live.append(
                matchId = matchId,
                sequence = live.lastSequence(matchId = matchId) + 1,
                kind = kind,
                side = null,
                playerId = playerId,
                recordedBy = server,
                recordedAt = LocalDateTime.now(),
            )
        }
    }

    private fun point(side: TeamSide) = LiveScoreEventRequest(kind = "POINT_WON", side = side.name)

    private fun bare(kind: String) = LiveScoreEventRequest(kind = kind)

    @Test
    fun `a plain player cannot score`() {
        user(uid = "nobody")
        val matchId = fixture()
        service
            .record(token = token(uid = "nobody"), matchId = matchId, request = point(side = TeamSide.TEAM1))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `an unknown caller cannot score`() {
        val matchId = fixture()
        service
            .record(token = token(uid = "ghost"), matchId = matchId, request = point(side = TeamSide.TEAM1))
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
            .record(token = token(uid = "ump"), matchId = matchId, request = point(side = TeamSide.TEAM1))
            .shouldBeRight()
            .pointsTeam1 shouldBe "15"
    }

    @Test
    fun `a HOST may score without holding SCORER, because SCORING_ROLES composes match management`() {
        user(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val matchId = fixture()
        service
            .record(token = token(uid = "host"), matchId = matchId, request = point(side = TeamSide.TEAM1))
            .shouldBeRight()
    }

    @Test
    fun `claiming moves a scheduled fixture to IN_PROGRESS`() {
        // MatchStatus.IN_PROGRESS has existed in the enum since the beginning with nothing writing it;
        // a live match is its first user.
        val id = umpire()
        val matchId = fixture()
        matches.findById(matchId = matchId).shouldBeRight().toDomain().status shouldBe MatchStatus.SCHEDULED

        service.claim(token = token(uid = "ump"), matchId = matchId).shouldBeRight().scorerId shouldBe id.toString()
        matches.findById(matchId = matchId).shouldBeRight().toDomain().status shouldBe MatchStatus.IN_PROGRESS
    }

    @Test
    fun `a second umpire takes the claim over rather than being refused`() {
        umpire(uid = "first")
        val second = umpire(uid = "second")
        val matchId = fixture()

        service.claim(token = token(uid = "first"), matchId = matchId).shouldBeRight()
        service.claim(token = token(uid = "second"), matchId = matchId).shouldBeRight().scorerId shouldBe second.toString()
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
            service.record(token = token(uid = "ump"), matchId = matchId, request = point(side = TeamSide.TEAM1))
        }
        val view = service.scoreboard(matchId = matchId)
        view.gamesTeam1 shouldBe 1
        // setup + 4 points + the serve rotation the completed game triggers (#985).
        view.sequence shouldBe (setupRows + 5).toLong()
    }

    @Test
    fun `undo appends a marker and takes the score back`() {
        umpire()
        val matchId = fixture()
        service.record(token = token(uid = "ump"), matchId = matchId, request = point(side = TeamSide.TEAM1))
        service.record(token = token(uid = "ump"), matchId = matchId, request = point(side = TeamSide.TEAM1))

        val undone = service.undo(token = token(uid = "ump"), matchId = matchId).shouldBeRight()
        undone.pointsTeam1 shouldBe "15"
        // Two points and the marker. Nothing was deleted.
        live.log(matchId = matchId).shouldHaveSize(size = setupRows + 3)
    }

    @Test
    fun `undo twice walks back two actions`() {
        umpire()
        val matchId = fixture()
        repeat(times = 3) {
            service.record(token = token(uid = "ump"), matchId = matchId, request = point(side = TeamSide.TEAM1))
        }
        service.undo(token = token(uid = "ump"), matchId = matchId).shouldBeRight()
        val twice = service.undo(token = token(uid = "ump"), matchId = matchId).shouldBeRight()

        // The second undo must target the second point, not the marker the first undo wrote.
        twice.pointsTeam1 shouldBe "15"
        live.log(matchId = matchId).shouldHaveSize(size = setupRows + 5)
    }

    @Test
    fun `undo with nothing to undo is not an error and writes nothing`() {
        umpire()
        // A scheduled fixture, deliberately: `fixture` seeds a start and a server, and those ARE
        // undoable actions — so on a match under way there is always something to take back.
        val matchId = scheduledFixture()

        service.undo(token = token(uid = "ump"), matchId = matchId).shouldBeRight().sequence shouldBe 0L
        live.log(matchId = matchId).shouldHaveSize(size = 0)
    }

    @Test
    fun `undoing everything and scoring again continues from the surviving log`() {
        umpire()
        val matchId = fixture()
        service.record(token = token(uid = "ump"), matchId = matchId, request = point(side = TeamSide.TEAM1))
        service.undo(token = token(uid = "ump"), matchId = matchId).shouldBeRight()

        val after =
            service
                .record(token = token(uid = "ump"), matchId = matchId, request = point(side = TeamSide.TEAM2))
                .shouldBeRight()
        after.pointsTeam1 shouldBe "0"
        after.pointsTeam2 shouldBe "15"
    }

    @Test
    fun `start, pause and resume round-trip through the database`() {
        umpire()
        val matchId = fixture()

        service.record(token = token(uid = "ump"), matchId = matchId, request = bare(kind = "MATCH_STARTED")).shouldBeRight()
        service.record(token = token(uid = "ump"), matchId = matchId, request = point(side = TeamSide.TEAM1))
        val paused =
            service
                .record(token = token(uid = "ump"), matchId = matchId, request = bare(kind = "PAUSED"))
                .shouldBeRight()
        paused.isPaused shouldBe true
        paused.hasStarted shouldBe true

        val resumed =
            service
                .record(token = token(uid = "ump"), matchId = matchId, request = bare(kind = "RESUMED"))
                .shouldBeRight()
        resumed.isPaused shouldBe false
        // The score is untouched by the suspension.
        resumed.pointsTeam1 shouldBe "15"
    }

    @Test
    fun `a paused match keeps its log indefinitely, since a rain delay has no deadline`() {
        // The reason there is no staleness sweep: a suspended match may resume days later on a court the
        // host cannot yet book, so no timeout could tell "abandoned" from "waiting for weather".
        umpire()
        val matchId = fixture()
        service.record(token = token(uid = "ump"), matchId = matchId, request = bare(kind = "MATCH_STARTED"))
        service.record(token = token(uid = "ump"), matchId = matchId, request = bare(kind = "PAUSED"))

        live.log(matchId = matchId).shouldHaveSize(size = setupRows + 2)
        service.scoreboard(matchId = matchId).isPaused shouldBe true
    }

    @Test
    fun `every recorded action is timestamped, so durations are derivable`() {
        umpire()
        val matchId = fixture()
        service.record(token = token(uid = "ump"), matchId = matchId, request = bare(kind = "MATCH_STARTED"))
        service.record(token = token(uid = "ump"), matchId = matchId, request = point(side = TeamSide.TEAM1))

        val rows = live.log(matchId = matchId)
        rows.shouldHaveSize(size = setupRows + 2)
        // Non-decreasing, and every row carries one — the anchor a match-duration figure is measured from.
        (rows[1].recordedAt >= rows[0].recordedAt) shouldBe true
    }

    @Test
    fun `the recording umpire is attributed on every row`() {
        val id = umpire()
        val matchId = fixture()
        service.record(token = token(uid = "ump"), matchId = matchId, request = point(side = TeamSide.TEAM1))
        service.undo(token = token(uid = "ump"), matchId = matchId)

        // Past the rows `fixture` seeded, which are arrangement rather than anything the umpire did.
        live.log(matchId = matchId).drop(n = setupRows).map { it.recordedBy }.toSet() shouldBe setOf(element = id)
    }

    @Test
    fun `a disabled match cannot be scored`() {
        umpire()
        val matchId = fixture()
        matches.setActive(matchId = matchId, active = false, disabledAt = java.time.LocalDateTime.now())

        service
            .record(token = token(uid = "ump"), matchId = matchId, request = point(side = TeamSide.TEAM1))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `a rated match cannot be scored, because its result already fed ratings and points`() {
        val id = umpire()
        val matchId = fixture()
        matches.markRated(matchId = matchId, ratedAt = java.time.LocalDateTime.now(), ratedBy = id)

        service
            .record(token = token(uid = "ump"), matchId = matchId, request = point(side = TeamSide.TEAM1))
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
            .record(token = token(uid = "ump"), matchId = matchId, request = point(side = TeamSide.TEAM1))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()

        // Bounded, and it really did try more than once.
        alwaysLoses.attempts shouldBe 3
    }

    @Test
    fun `every write tells spectators, and a no-op undo does not`() {
        // One place can forget the broadcast, so one test covers all of it. The no-op undo matters
        // separately: a courtside double-tap must not push a redundant document at every spectator.
        val sent = mutableListOf<LiveScorePayload>()
        val watched = LiveMatchService(broadcast = { payload -> sent += payload })
        umpire()
        val matchId = fixture()

        watched.claim(token = token(uid = "ump"), matchId = matchId).shouldBeRight()
        watched.record(token = token(uid = "ump"), matchId = matchId, request = point(side = TeamSide.TEAM1)).shouldBeRight()
        // Three undos: the point, then the server and start rows `fixture` seeded. Only with the log
        // empty is the next undo genuinely a no-op, which is the case under test.
        repeat(times = 3) { watched.undo(token = token(uid = "ump"), matchId = matchId).shouldBeRight() }
        watched.release(token = token(uid = "ump"), matchId = matchId).shouldBeRight()
        val beforeNoOpUndo = sent.size

        // Nothing left to undo: no state change, so no broadcast.
        watched.undo(token = token(uid = "ump"), matchId = matchId).shouldBeRight()

        sent.size shouldBe beforeNoOpUndo
        // claim, record, three undos, release.
        beforeNoOpUndo shouldBe 6
        // Keyed by the public code, never the internal id: the document is world-readable.
        sent.last().publicCode.isNotBlank() shouldBe true
    }

    @Test
    fun `a failing broadcast never fails the umpire's write`() {
        // The log is the system of record and the broadcast is a projection of it. Trading a recorded
        // point for a stale scoreboard would be exactly the wrong way round, so the contract is that a
        // broadcaster swallows its own failures — and the service must not depend on it doing so.
        val exploding = LiveMatchService(broadcast = { error(message = "Firestore is down") })
        umpire()
        val matchId = fixture()

        shouldThrow<IllegalStateException> {
            exploding.record(token = token(uid = "ump"), matchId = matchId, request = point(side = TeamSide.TEAM1))
        }
        // The point still landed: the append is committed before anything is published.
        live.log(matchId = matchId).shouldHaveSize(size = setupRows + 1)
    }

    @Test
    fun `the clock runs from the official start and stops while paused (#937)`() {
        // A fixed clock, so this asserts the fold rather than how long the test took to run.
        var now = java.time.LocalDateTime.of(2026, 3, 1, 10, 0, 0)
        val timed = LiveMatchService(clock = { now })
        umpire()
        val matchId = scheduledFixture()

        // Scoring before the official start is refused now (#986) — the clock and the score agree that
        // the match has not begun, where before the clock said 0 while points accumulated beside it.
        timed.record(token = token(uid = "ump"), matchId = matchId, request = point(side = TeamSide.TEAM1)).shouldBeLeft()
        timed.scoreboard(matchId = matchId).elapsedSeconds shouldBe 0L

        timed.record(token = token(uid = "ump"), matchId = matchId, request = bare(kind = "MATCH_STARTED")).shouldBeRight()
        now = now.plusMinutes(5)
        timed.scoreboard(matchId = matchId).let {
            it.elapsedSeconds shouldBe 300L
            it.isRunning shouldBe true
        }

        timed.record(token = token(uid = "ump"), matchId = matchId, request = bare(kind = "PAUSED")).shouldBeRight()
        now = now.plusHours(3)
        // A rain delay is not playing time, and the client must stop ticking through it.
        timed.scoreboard(matchId = matchId).let {
            it.elapsedSeconds shouldBe 300L
            it.isRunning shouldBe false
        }

        timed.record(token = token(uid = "ump"), matchId = matchId, request = bare(kind = "RESUMED")).shouldBeRight()
        now = now.plusMinutes(2)
        timed.scoreboard(matchId = matchId).elapsedSeconds shouldBe 420L
    }

    @Test
    fun `a match with a recorded result cannot be scored, even before it is rated (#952)`() {
        // THE window this closes. Rating happens when the EVENT is finalized (#403), which can be days
        // after the match was recorded — and `ratedAt` was the only guard, so throughout that gap a
        // match with a full scoreline could still be claimed and scored.
        umpire()
        val matchId = fixture()
        matches.setStatus(matchId = matchId, status = MatchStatus.COMPLETED.name).shouldBeRight()

        // Deliberately NOT rated: that is the point of the test.
        matches.findById(matchId = matchId).shouldBeRight().toDomain().ratedAt shouldBe null

        service
            .record(token = token(uid = "ump"), matchId = matchId, request = point(side = TeamSide.TEAM1))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
        service
            .claim(token = token(uid = "ump"), matchId = matchId)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
        service
            .undo(token = token(uid = "ump"), matchId = matchId)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `a finished match's scoreboard is still readable (#952)`() {
        // The guard must not blind the spectator view: refusing to SCORE a recorded match is not the
        // same as refusing to SHOW it, and `scoreboard` deliberately does not go through that gate.
        umpire()
        val matchId = fixture()
        service.record(token = token(uid = "ump"), matchId = matchId, request = point(side = TeamSide.TEAM1))
        matches.setStatus(matchId = matchId, status = MatchStatus.COMPLETED.name).shouldBeRight()

        service.scoreboard(matchId = matchId).pointsTeam1 shouldBe "15"
    }

    @Test
    fun `a match still in progress is unaffected by the guard (#952)`() {
        // The line is "has a recorded result", not "has been started" — scoring a claimed match must
        // keep working, which is the thing the guard could most easily break.
        umpire()
        val matchId = fixture()
        service.claim(token = token(uid = "ump"), matchId = matchId).shouldBeRight()

        service
            .record(token = token(uid = "ump"), matchId = matchId, request = point(side = TeamSide.TEAM1))
            .shouldBeRight()
    }

    @Test
    fun `an unknown match is not found`() {
        umpire()
        service
            .record(token = token(uid = "ump"), matchId = UUID.randomUUID(), request = point(side = TeamSide.TEAM1))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }
}
