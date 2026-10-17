// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.contract.ReplayDocument
import org.skopeo.common.contract.ReplayScorer
import org.skopeo.common.dto.livematch.LiveMatchSweepRequest
import org.skopeo.common.dto.match.MatchReplayBackfillRequest
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.livematch.LiveMatchEventKinds
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.Match
import org.skopeo.domain.model.MatchSetResult
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.LiveMatchRepository
import org.skopeo.repository.MatchReplayRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.fixtureEventId
import org.skopeo.testsupport.seedEvent
import org.skopeo.testsupport.seedFixtureClub
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Recording, showing, backfilling and upgrading match replays against a real database (#1145).
 *
 * The rule most worth pinning is visibility: a replay is shown only while it still leads to the result on
 * the match page, so editing the result hides it without deleting it.
 */
class MatchReplayServiceTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val live = LiveMatchRepository()
    private val matches = MatchRepository()
    private val replays = MatchReplayRepository()
    private val service = MatchReplayService()
    private val start: LocalDateTime = LocalDateTime.of(2026, 6, 1, 9, 0)

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
                        firebaseUid = uid,
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                        capabilities = roles,
                    ),
            ).toDomain()
            .id

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private fun admin(): VerifiedFirebaseToken {
        user(uid = "boss", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        return token(uid = "boss")
    }

    private fun fixture(
        name: String,
        eventId: UUID? = null,
    ): Match {
        val home = user(uid = "$name-home")
        val away = user(uid = "$name-away")
        return matches
            .createFixture(
                command =
                    CreateFixtureCommand(
                        matchFormat = TeamType.SINGLES,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = LocalDate.of(2026, 6, 1),
                        team1UserIds = listOf(element = home),
                        team2UserIds = listOf(element = away),
                        team1Name = "$name-home",
                        team2Name = "$name-away",
                        createdBy = home,
                        eventId = eventId ?: fixtureEventId(home, away),
                    ),
            ).toDomain()
    }

    /** Append [rows] (kind, side, undo target) to [match]'s log, ten seconds apart from [start]. */
    private fun log(
        match: Match,
        rows: List<Triple<String, String?, Long?>>,
        at: LocalDateTime = start,
    ) {
        rows.forEachIndexed { index, (kind, side, target) ->
            live.append(
                matchId = match.id,
                sequence = index + 1L,
                kind = kind,
                side = side,
                targetSequence = target,
                recordedBy = match.team1.userIds.first(),
                recordedAt = at.plusSeconds(index * 10L),
            )
        }
    }

    private fun row(
        kind: String,
        side: String? = null,
        target: Long? = null,
    ) = Triple(first = kind, second = side, third = target)

    /**
     * One game to TEAM1, with a mistaken point to TEAM2 keyed in and undone at 40-0, then the match
     * awarded. Eleven rows: four to start, four points, the undo, the game point, the award.
     */
    private fun oneGameLog(): List<Triple<String, String?, Long?>> =
        listOf(
            row(kind = LiveMatchEventKinds.MATCH_STARTED),
            row(kind = LiveMatchEventKinds.SERVER_ASSIGNED, side = "TEAM1"),
            row(kind = LiveMatchEventKinds.SET_STARTED),
            row(kind = LiveMatchEventKinds.GAME_STARTED),
            row(kind = LiveMatchEventKinds.POINT_WON, side = "TEAM1"),
            row(kind = LiveMatchEventKinds.POINT_WON, side = "TEAM1"),
            row(kind = LiveMatchEventKinds.POINT_WON, side = "TEAM1"),
            row(kind = LiveMatchEventKinds.POINT_WON, side = "TEAM2"),
            row(kind = LiveMatchEventKinds.UNDONE, target = 8),
            row(kind = LiveMatchEventKinds.POINT_WON, side = "TEAM1"),
            row(kind = LiveMatchEventKinds.MATCH_AWARDED, side = "TEAM1"),
        )

    /** Record [games] as [match]'s result with TEAM1 the winner — what finalize writes for [oneGameLog]. */
    private fun recordResult(
        match: Match,
        games: Pair<Int, Int> = 1 to 0,
    ) {
        matches
            .addResult(
                matchId = match.id,
                sets =
                    listOf(
                        element =
                            MatchSetResult(
                                setNumber = 1,
                                team1Games = games.first,
                                team2Games = games.second,
                                winnerTeamId = match.team1.teamId,
                                abandoned = true,
                            ),
                    ),
                winnerTeamId = match.team1.teamId,
                recordedBy = match.team1.userIds.first(),
                completedAt = start.plusHours(1),
            ).shouldBeRight()
    }

    private fun storedDocument(match: Match): ReplayDocument =
        Json.decodeFromString<ReplayDocument>(string = replays.find(matchId = match.id).shouldNotBeNull().replay)

    /** [document] as format 1 stored it: no scorer on any event or point. */
    private fun asVersionOne(document: ReplayDocument): ReplayDocument =
        document.copy(
            version = 1,
            events = document.events.map { it.copy(scorer = null) },
            points = document.points.map { it.copy(scorer = null) },
        )

    /** The public code of [match]'s home player — who [log] attributes every row to. */
    private fun homeCode(match: Match): String =
        UserRepository()
            .findById(id = match.team1.userIds.first())
            .shouldBeRight()
            .toDomain()
            .publicCode

    private fun finishedMatch(name: String = "m"): Match {
        val match = fixture(name = name)
        log(match = match, rows = oneGameLog())
        recordResult(match = match)
        return match
    }

    @Test
    fun `a recorded replay is shown by public code, without the undone point`() {
        val match = finishedMatch()

        service.recordFromLog(matchId = match.id) shouldBe true
        val replay = service.byCode(code = match.publicCode).shouldBeRight()

        replay.points.map { it.kind } shouldBe listOf("POINT", "POINT", "POINT", "POINT", "END")
        replay.points.none { it.wonBy == "TEAM2" } shouldBe true
        // The undone row and its marker are gone from the stored source too.
        replay.events shouldHaveSize 9
        replay.events.first().atMs shouldBe 0L
        // The game-winning point was the tenth row: 90 seconds after the first.
        replay.points[3].atMs shouldBe 90_000L
        replay.points[3].gameWon shouldBe true
        // Stored with every step's scorer, but an anonymous caller is shown none of them (#1183).
        replay.version shouldBe 2
        replay.scorers.shouldBeNull()
        replay.points.all { it.scorer == null } shouldBe true
        storedDocument(match = match).points.map { it.scorer }.toSet() shouldBe setOf(element = homeCode(match = match))
    }

    @Test
    fun `editing the result away from the replay hides it, and stamps the match as edited`() {
        val match = finishedMatch()
        service.recordFromLog(matchId = match.id)

        recordResult(match = match, games = 2 to 0)

        service.byCode(code = match.publicCode).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
        matches
            .findById(matchId = match.id)
            .shouldBeRight()
            .toDomain()
            .resultEditedAt
            .shouldNotBeNull()
        // Kept, not deleted: editing the result back would show it again.
        replays.find(matchId = match.id).shouldNotBeNull()
        recordResult(match = match)
        service.byCode(code = match.publicCode).shouldBeRight()
    }

    @Test
    fun `the first recording of a result is not an edit`() {
        val match = finishedMatch()

        matches
            .findById(matchId = match.id)
            .shouldBeRight()
            .toDomain()
            .resultEditedAt
            .shouldBeNull()
    }

    @Test
    fun `no match, no replay, and no result are all a 404`() {
        service.byCode(code = "NOPE00").shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()

        val unrecorded = finishedMatch(name = "unrecorded")
        service.byCode(code = unrecorded.publicCode).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()

        // Recorded from a finished log, but the match itself never had its result written.
        val resultless = fixture(name = "resultless")
        log(match = resultless, rows = oneGameLog())
        service.recordFromLog(matchId = resultless.id) shouldBe true
        service.byCode(code = resultless.publicCode).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `a log that never ends records nothing`() {
        val match = fixture(name = "unfinished")
        log(match = match, rows = oneGameLog().dropLast(n = 1))

        service.recordFromLog(matchId = match.id) shouldBe false
        service.recordFromLog(matchId = fixture(name = "empty").id) shouldBe false
        replays.find(matchId = match.id).shouldBeNull()
    }

    @Test
    fun `ensureRecorded leaves an existing replay alone`() {
        val match = finishedMatch()
        replays.save(matchId = match.id, formatVersion = 1, replay = """{"marker":true}""")

        service.ensureRecorded(matchId = match.id) shouldBe true

        replays.find(matchId = match.id).shouldNotBeNull().replay shouldBe """{"marker": true}"""
    }

    @Test
    fun `only an administrator may backfill`() {
        user(uid = "player")
        service
            .backfill(token = token(uid = "player"), request = MatchReplayBackfillRequest(dryRun = false))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `a backfill previews by default and records on request, skipping logs that never end`() {
        val boss = admin()
        val finished = finishedMatch(name = "finished")
        val unended = fixture(name = "unended")
        log(match = unended, rows = oneGameLog().dropLast(n = 1))
        recordResult(match = unended)

        val preview = service.backfill(token = boss, request = MatchReplayBackfillRequest()).shouldBeRight()
        preview.dryRun shouldBe true
        preview.recorded shouldBe 1
        preview.skipped shouldBe 1
        replays.find(matchId = finished.id).shouldBeNull()

        val run = service.backfill(token = boss, request = MatchReplayBackfillRequest(dryRun = false)).shouldBeRight()
        run.recorded shouldBe 1
        service.byCode(code = finished.publicCode).shouldBeRight()

        // Nothing left to record the second time round.
        service.backfill(token = boss, request = MatchReplayBackfillRequest(dryRun = false)).shouldBeRight().recorded shouldBe 0
    }

    @Test
    fun `an older format is regenerated from its stored events, on read and by the backfill`() {
        val boss = admin()
        val match = finishedMatch()
        service.recordFromLog(matchId = match.id)
        // Stand in for an older format: the source survives, the derived timeline does not.
        val stored = asVersionOne(document = storedDocument(match = match))
        replays.save(matchId = match.id, formatVersion = 1, replay = Json.encodeToString(value = stored.copy(points = emptyList())))
        // The log can be gone by then — the sweep deletes it — so it must not be what the upgrade reads.
        live.discardLog(matchId = match.id)

        service.backfill(token = boss, request = MatchReplayBackfillRequest()).shouldBeRight().upgraded shouldBe 1
        val upgraded = service.byCode(code = match.publicCode, token = boss).shouldBeRight()
        upgraded.version shouldBe ReplayBuilder.CURRENT_VERSION
        upgraded.points shouldBe stored.points
        // Without the log there is nobody to name: an upgraded v1 simply has no scorers (#1183).
        upgraded.points.all { it.scorer == null } shouldBe true
        upgraded.scorers shouldBe emptyMap()
    }

    @Test
    fun `an older format whose log survives is re-recorded from it, gaining scorers, on read and by the backfill (#1183)`() {
        val boss = admin()
        val match = finishedMatch()
        service.recordFromLog(matchId = match.id)
        val v1 = asVersionOne(document = storedDocument(match = match))
        replays.save(matchId = match.id, formatVersion = 1, replay = Json.encodeToString(value = v1))
        val home = homeCode(match = match)

        service.backfill(token = boss, request = MatchReplayBackfillRequest()).shouldBeRight().upgraded shouldBe 1
        val upgraded = service.byCode(code = match.publicCode, token = boss).shouldBeRight()

        upgraded.points.map { it.scorer }.toSet() shouldBe setOf(element = home)
        upgraded.events.map { it.scorer }.toSet() shouldBe setOf(element = home)
        replays.find(matchId = match.id).shouldNotBeNull().formatVersion shouldBe ReplayBuilder.CURRENT_VERSION
    }

    @Test
    fun `a surviving log that no longer ends at the replay's result is not used for the upgrade`() {
        val boss = admin()
        val match = finishedMatch()
        service.recordFromLog(matchId = match.id)
        val v1 = asVersionOne(document = storedDocument(match = match))
        replays.save(matchId = match.id, formatVersion = 1, replay = Json.encodeToString(value = v1))
        // The log now ends with the match going the other way — not the match this replay recorded.
        live.append(
            matchId = match.id,
            sequence = 12,
            kind = LiveMatchEventKinds.UNDONE,
            targetSequence = 11,
            recordedBy = match.team1.userIds.first(),
            recordedAt = start.plusMinutes(5),
        )
        live.append(
            matchId = match.id,
            sequence = 13,
            kind = LiveMatchEventKinds.MATCH_AWARDED,
            side = "TEAM2",
            recordedBy = match.team1.userIds.first(),
            recordedAt = start.plusMinutes(6),
        )

        val upgraded = service.byCode(code = match.publicCode, token = boss).shouldBeRight()

        upgraded.result shouldBe v1.result
        upgraded.points.all { it.scorer == null } shouldBe true
    }

    @Test
    fun `per-point scorers reach the event's organizers and administrators only (#1183)`() {
        user(uid = "boss", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        user(uid = "fan")
        user(uid = "owner", roles = setOf(Capability.PLAYER, Capability.HOST))
        user(uid = "stranger", roles = setOf(Capability.PLAYER, Capability.HOST))
        // The stranger organizes an event of their own, under a club of their own — just not this one.
        seedEvent(name = "Elsewhere", clubId = seedFixtureClub(ownerUids = arrayOf("stranger"), name = "Elsewhere TC").id)
        val match = fixture(name = "club", eventId = seedEvent(name = "Cup", clubId = seedFixtureClub(ownerUids = arrayOf("owner")).id).id)
        log(match = match, rows = oneGameLog())
        recordResult(match = match)
        service.recordFromLog(matchId = match.id)
        val home = homeCode(match = match)

        val seen =
            mapOf(
                "anonymous" to null,
                "fan" to token(uid = "fan"),
                "stranger" to token(uid = "stranger"),
                "owner" to token(uid = "owner"),
                "boss" to token(uid = "boss"),
            ).mapValues { (_, caller) -> service.byCode(code = match.publicCode, token = caller).shouldBeRight() }

        seen.filterValues { it.scorers != null }.keys shouldBe setOf("owner", "boss")
        seen.forEach { (who, replay) ->
            val entitled = who == "owner" || who == "boss"
            replay.points.all { (it.scorer == home) == entitled } shouldBe true
            replay.events.all { (it.scorer == home) == entitled } shouldBe true
        }
        seen.getValue(key = "owner").scorers shouldBe mapOf(pair = home to ReplayScorer(displayName = "club-home"))
    }

    @Test
    fun `a deleted scorer is shown as deleted and never by name (#1183)`() {
        val boss = admin()
        val match = finishedMatch()
        service.recordFromLog(matchId = match.id)
        UserRepository().deactivate(id = match.team1.userIds.first()).shouldBeRight()

        val scorers =
            service
                .byCode(code = match.publicCode, token = boss)
                .shouldBeRight()
                .scorers
                .shouldNotBeNull()

        scorers shouldBe mapOf(pair = homeCode(match = match) to ReplayScorer(displayName = null, isDeleted = true))
    }

    @Test
    fun `the sweep records a missing replay before it deletes the log`() {
        val boss = admin()
        val match = fixture(name = "old")
        log(match = match, rows = oneGameLog(), at = start.minusDays(200))
        recordResult(match = match)

        LiveMatchSweepService(clock = { start })
            .sweep(token = boss, request = LiveMatchSweepRequest(dryRun = false))
            .shouldBeRight()
            .prunedMatches shouldBe 1

        live.log(matchId = match.id) shouldHaveSize 0
        service.byCode(code = match.publicCode).shouldBeRight().points shouldHaveSize 5
    }
}
