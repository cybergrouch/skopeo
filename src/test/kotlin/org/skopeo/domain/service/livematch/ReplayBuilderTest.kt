// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skopeo.common.contract.ReplayResult
import org.skopeo.common.contract.ReplaySet
import org.skopeo.domain.model.ScoreEvent
import org.skopeo.domain.model.TeamSide

/**
 * The replay timeline (#1145). Pure, so every flag is asserted on a hand-built log rather than through a
 * database — the service tests cover where the log comes from.
 */
class ReplayBuilderTest {
    private val t1 = TeamSide.TEAM1
    private val t2 = TeamSide.TEAM2

    /** [events] timed one second apart, the shape the service hands in. */
    private fun timed(events: List<ScoreEvent>): List<TimedScoreEvent> =
        events.mapIndexed { index, event -> TimedScoreEvent(event = event, atMs = index * 1_000L) }

    private fun opening(serving: TeamSide): List<ScoreEvent> =
        listOf(ScoreEvent.MatchStarted, ScoreEvent.ServerAssigned(side = serving), ScoreEvent.SetStarted, ScoreEvent.GameStarted)

    private fun points(
        side: TeamSide,
        count: Int,
    ): List<ScoreEvent> = List(size = count) { ScoreEvent.PointWon(side = side) }

    @Test
    fun `a broken service game flags its break points and the break`() {
        val log =
            opening(serving = t1) + points(side = t2, count = 3) + points(side = t1, count = 1) + points(side = t2, count = 1) +
                ScoreEvent.SetAwarded(side = t2) + ScoreEvent.MatchAwarded(side = t2)

        val replay = ReplayBuilder.build(events = timed(events = log)).shouldNotBeNull()

        replay.version shouldBe ReplayBuilder.CURRENT_VERSION
        replay.events shouldHaveSize log.size
        val steps = replay.points
        steps.map { it.kind } shouldBe listOf("POINT", "POINT", "POINT", "POINT", "POINT", "SET", "END")
        steps.map { it.index } shouldBe (0..6).toList()
        steps.map { "${it.score.pointsTeam1}-${it.score.pointsTeam2}" }.take(n = 4) shouldBe listOf("0-15", "0-30", "0-40", "15-40")
        // 0-40 and 15-40 were break points; 0-0, 0-15 and 0-30 were not.
        steps.take(n = 5).map { it.breakPoint } shouldBe listOf(false, false, false, true, true)
        val game = steps[4]
        game.gameWon shouldBe true
        game.breakOfServe shouldBe true
        game.server shouldBe "TEAM1"
        game.score.gamesTeam2 shouldBe 1
        game.atMs shouldBe 8_000L
        val set = steps[5]
        set.setWon shouldBe true
        set.gameWon shouldBe false
        set.score.sets shouldBe listOf(element = ReplaySet(team1 = 0, team2 = 1))
        val end = steps[6]
        end.outcome shouldBe "COMPLETED"
        end.wonBy shouldBe "TEAM2"
        // Ended between sets, so it closes the set just banked rather than opening a second.
        end.setNumber shouldBe 1
        replay.result shouldBe ReplayResult(sets = listOf(element = ReplaySet(team1 = 0, team2 = 1)), winner = "TEAM2")
    }

    @Test
    fun `a held service game is not a break`() {
        val log = opening(serving = t1) + points(side = t1, count = 4) + ScoreEvent.MatchAwarded(side = t1)

        val game = ReplayBuilder.build(events = timed(events = log)).shouldNotBeNull().points[3]

        game.gameWon shouldBe true
        game.breakOfServe shouldBe false
        game.breakPoint shouldBe false
    }

    @Test
    fun `a tiebreak is scored by count and its set award counts as the deciding game`() {
        val log =
            listOf(ScoreEvent.MatchStarted, ScoreEvent.ServerAssigned(side = t1), ScoreEvent.SetStarted, ScoreEvent.TiebreakStarted) +
                points(side = t1, count = 7) + ScoreEvent.SetAwarded(side = t1) + ScoreEvent.MatchAwarded(side = t1)

        val replay = ReplayBuilder.build(events = timed(events = log)).shouldNotBeNull()

        val tiebreakPoints = replay.points.filter { it.kind == "POINT" }
        tiebreakPoints shouldHaveSize 7
        tiebreakPoints.all { it.tiebreak && !it.breakPoint && !it.gameWon } shouldBe true
        tiebreakPoints.last().score.pointsTeam1 shouldBe "7"
        val set = replay.points.single { it.kind == "SET" }
        set.tiebreak shouldBe true
        set.gameWon shouldBe true
        set.setWon shouldBe true
        // The tiebreak alternates serve, so who "broke" is not a meaningful question inside one.
        set.breakOfServe shouldBe false
        replay.result.sets shouldBe listOf(element = ReplaySet(team1 = 1, team2 = 0, tiebreakTeam1 = 7, tiebreakTeam2 = 0))
    }

    @Test
    fun `a retirement ends the replay with the partial set as the result`() {
        val log = opening(serving = t1) + points(side = t1, count = 4) + ScoreEvent.Retired(side = t2)

        val replay = ReplayBuilder.build(events = timed(events = log)).shouldNotBeNull()

        val end = replay.points.last()
        end.kind shouldBe "END"
        end.outcome shouldBe "RETIRED"
        end.wonBy shouldBe "TEAM1"
        end.setNumber shouldBe 1
        replay.result shouldBe ReplayResult(sets = listOf(element = ReplaySet(team1 = 1, team2 = 0)), winner = "TEAM1")
    }

    @Test
    fun `a game awarded by the umpire is a step of its own`() {
        val log = opening(serving = t2) + ScoreEvent.GameAwarded(side = t1) + ScoreEvent.MatchAwarded(side = t1)

        val game = ReplayBuilder.build(events = timed(events = log)).shouldNotBeNull().points.first()

        game.kind shouldBe "GAME"
        game.gameWon shouldBe true
        game.breakOfServe shouldBe true
        game.breakPoint shouldBe false
    }

    @Test
    fun `scoring after the end is no step, and with no server nothing is a break`() {
        val log =
            listOf(ScoreEvent.MatchStarted, ScoreEvent.SetStarted, ScoreEvent.GameStarted) + points(side = t2, count = 4) +
                ScoreEvent.MatchAwarded(side = t2) + ScoreEvent.PointWon(side = t1)

        val replay = ReplayBuilder.build(events = timed(events = log)).shouldNotBeNull()

        replay.points.map { it.kind } shouldBe listOf("POINT", "POINT", "POINT", "POINT", "END")
        replay.points.none { it.breakPoint || it.breakOfServe } shouldBe true
        replay.points.first().server.shouldBeNull()
    }

    @Test
    fun `a log that never ends has no replay`() {
        ReplayBuilder.build(events = timed(events = opening(serving = t1) + points(side = t1, count = 3))).shouldBeNull()
        ReplayBuilder.build(events = emptyList()).shouldBeNull()
    }

    @Test
    fun `rebuilding from the stored events reproduces the document`() {
        val log =
            opening(serving = t1) + points(side = t2, count = 4) + ScoreEvent.Paused + ScoreEvent.Resumed +
                ScoreEvent.SetAwarded(side = t2) + ScoreEvent.Defaulted(side = t1)
        val replay = ReplayBuilder.build(events = timed(events = log)).shouldNotBeNull()

        ReplayBuilder.rebuild(document = replay) shouldBe replay
        replay.points.last().outcome shouldBe "DEFAULTED"
    }
}
