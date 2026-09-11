// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skopeo.domain.model.LiveOutcomeKind
import org.skopeo.domain.model.LoggedAction
import org.skopeo.domain.model.ScoreEvent
import org.skopeo.domain.model.ScoreState
import org.skopeo.domain.model.TeamSide
import java.util.UUID

/**
 * The scoring rules (#911 step 2). Pure functions, so none of this needs a database or a mock.
 *
 * The engine is deliberately **permissive**: it advances points and closes a game, and the set, the
 * tiebreak and the match are declared by the umpire. Several tests below assert things a tennis purist
 * would call wrong — a set banked at 2-1, a tiebreak reaching 7-0 without ending. Those are the
 * requirement (#911 asks that a set be endable below six games and that the UI not presume a tiebreak
 * target), so they are pinned rather than left to chance.
 */
class ScoreEngineTest {
    /**
     * Fold [events] onto a **started** match.
     *
     * Scoring is inert until `MATCH_STARTED` (#986), so every test whose subject is a scoring rule
     * needs the match under way first. Prepending it here rather than in twenty tests keeps each one
     * about its own rule. Tests whose subject IS the gating build their state explicitly instead.
     */
    private fun state(vararg events: ScoreEvent): ScoreState = stateOf(events = events.toList())

    private fun points(
        side: TeamSide,
        times: Int,
    ): List<ScoreEvent> = List(size = times) { ScoreEvent.PointWon(side = side) }

    private fun stateOf(events: List<ScoreEvent>): ScoreState =
        (listOf(element = ScoreEvent.MatchStarted) + events)
            .fold(initial = ScoreState()) { acc, event -> ScoreEngine.apply(state = acc, event = event) }

    @Test
    fun `a fresh state is love-all with nothing banked`() {
        val fresh = ScoreState()
        fresh.displayPoints(side = TeamSide.TEAM1) shouldBe "0"
        fresh.displayPoints(side = TeamSide.TEAM2) shouldBe "0"
        fresh.gamesTeam1 shouldBe 0
        fresh.completedSets.shouldHaveSize(size = 0)
        fresh.isFinished shouldBe false
        fresh.serverId shouldBe null
    }

    @Test
    fun `points render as 0, 15, 30, 40 as they accumulate`() {
        listOf(0 to "0", 1 to "15", 2 to "30", 3 to "40").forEach { (count, shown) ->
            stateOf(events = points(side = TeamSide.TEAM1, times = count))
                .displayPoints(side = TeamSide.TEAM1) shouldBe shown
        }
    }

    @Test
    fun `a fourth point with two clear wins the game and resets the points`() {
        val after = stateOf(events = points(side = TeamSide.TEAM1, times = 4))
        after.gamesTeam1 shouldBe 1
        after.gamesTeam2 shouldBe 0
        after.pointsTeam1 shouldBe 0
        after.pointsTeam2 shouldBe 0
    }

    @Test
    fun `40-30 is not a game, the fourth point must lead by two`() {
        // 3-2 up, then a point: 4-2 closes it. But 3-3 then 4-3 must NOT.
        val fortyThirty = stateOf(events = points(side = TeamSide.TEAM1, times = 3) + points(side = TeamSide.TEAM2, times = 2))
        fortyThirty.gamesTeam1 shouldBe 0

        val closed = ScoreEngine.apply(state = fortyThirty, event = ScoreEvent.PointWon(side = TeamSide.TEAM1))
        closed.gamesTeam1 shouldBe 1
    }

    @Test
    fun `three-all is deuce, and the next point is advantage rather than a game`() {
        val deuce = stateOf(events = points(side = TeamSide.TEAM1, times = 3) + points(side = TeamSide.TEAM2, times = 3))
        deuce.displayPoints(side = TeamSide.TEAM1) shouldBe "40"
        deuce.displayPoints(side = TeamSide.TEAM2) shouldBe "40"

        val advantage = ScoreEngine.apply(state = deuce, event = ScoreEvent.PointWon(side = TeamSide.TEAM1))
        advantage.gamesTeam1 shouldBe 0
        advantage.displayPoints(side = TeamSide.TEAM1) shouldBe "AD"
        advantage.displayPoints(side = TeamSide.TEAM2) shouldBe "40"
    }

    @Test
    fun `advantage lost returns to deuce, and the game closes only two clear`() {
        val deuce = stateOf(events = points(side = TeamSide.TEAM1, times = 3) + points(side = TeamSide.TEAM2, times = 3))
        val advantage = ScoreEngine.apply(state = deuce, event = ScoreEvent.PointWon(side = TeamSide.TEAM1))
        val backToDeuce = ScoreEngine.apply(state = advantage, event = ScoreEvent.PointWon(side = TeamSide.TEAM2))

        backToDeuce.displayPoints(side = TeamSide.TEAM1) shouldBe "40"
        backToDeuce.displayPoints(side = TeamSide.TEAM2) shouldBe "40"
        backToDeuce.gamesTeam1 shouldBe 0

        val advantageAgain = ScoreEngine.apply(state = backToDeuce, event = ScoreEvent.PointWon(side = TeamSide.TEAM1))
        val game = ScoreEngine.apply(state = advantageAgain, event = ScoreEvent.PointWon(side = TeamSide.TEAM1))
        game.gamesTeam1 shouldBe 1
        game.pointsTeam1 shouldBe 0
    }

    @Test
    fun `a game that goes through deuce many times still closes on two clear`() {
        // Ten deuces. The rule is expressed generally, so 14-12 closes exactly like 4-2 — this pins that
        // there is no special-casing of the first deuce hiding in the arithmetic.
        val manyDeuces =
            points(side = TeamSide.TEAM1, times = 3) + points(side = TeamSide.TEAM2, times = 3) +
                (1..10).flatMap { points(side = TeamSide.TEAM1, times = 1) + points(side = TeamSide.TEAM2, times = 1) }
        val deuce = stateOf(events = manyDeuces)
        deuce.gamesTeam1 shouldBe 0
        deuce.displayPoints(side = TeamSide.TEAM1) shouldBe "40"

        val closed = stateOf(events = manyDeuces + points(side = TeamSide.TEAM2, times = 2))
        closed.gamesTeam2 shouldBe 1
    }

    @Test
    fun `the umpire can award a game outright regardless of the points`() {
        val midGame = stateOf(events = points(side = TeamSide.TEAM1, times = 2))
        val awarded = ScoreEngine.apply(state = midGame, event = ScoreEvent.GameAwarded(side = TeamSide.TEAM2))
        awarded.gamesTeam2 shouldBe 1
        awarded.gamesTeam1 shouldBe 0
        awarded.pointsTeam1 shouldBe 0
    }

    @Test
    fun `winning six games does not end the set, because the umpire declares it`() {
        // The permissive boundary, stated as a test: a 6-0 set stays open until SetAwarded. Auto-closing
        // would fight the umpire in any short-set or pro-set format (#911).
        val sixLove = stateOf(events = (1..6).flatMap { points(side = TeamSide.TEAM1, times = 4) })
        sixLove.gamesTeam1 shouldBe 6
        sixLove.completedSets.shouldHaveSize(size = 0)

        val banked = ScoreEngine.apply(state = sixLove, event = ScoreEvent.SetAwarded(side = TeamSide.TEAM1))
        banked.completedSets.shouldHaveSize(size = 1)
        banked.completedSets.single().gamesTeam1 shouldBe 6
        banked.completedSets.single().winner shouldBe TeamSide.TEAM1
        banked.gamesTeam1 shouldBe 0
    }

    @Test
    fun `a set can be ended below six games`() {
        // Daylight ran out at 3-2. #911 requires this to be recordable.
        val short =
            stateOf(
                events =
                    (1..3).flatMap { points(side = TeamSide.TEAM1, times = 4) } +
                        (1..2).flatMap { points(side = TeamSide.TEAM2, times = 4) },
            )
        val banked = ScoreEngine.apply(state = short, event = ScoreEvent.SetAwarded(side = TeamSide.TEAM1))
        banked.completedSets.single().gamesTeam1 shouldBe 3
        banked.completedSets.single().gamesTeam2 shouldBe 2
        banked.completedSets.single().winner shouldBe TeamSide.TEAM1
    }

    @Test
    fun `a set may be awarded to the side with fewer games`() {
        // Not a mistake to guard against: the umpire is authoritative, and a set conceded mid-way is a
        // real thing. If this ever needs refusing it belongs in the API, not in a pure step function.
        val behind = stateOf(events = (1..4).flatMap { points(side = TeamSide.TEAM1, times = 4) })
        val banked = ScoreEngine.apply(state = behind, event = ScoreEvent.SetAwarded(side = TeamSide.TEAM2))
        banked.completedSets.single().winner shouldBe TeamSide.TEAM2
        banked.completedSets.single().gamesTeam1 shouldBe 4
        banked.completedSets.single().gamesTeam2 shouldBe 0
    }

    @Test
    fun `tiebreak points are plain ordinals and never close a game on their own`() {
        val inTiebreak = state(ScoreEvent.TiebreakStarted)
        val sevenLove = stateOf(events = listOf(element = ScoreEvent.TiebreakStarted) + points(side = TeamSide.TEAM1, times = 7))

        inTiebreak.isTiebreak shouldBe true
        sevenLove.displayPoints(side = TeamSide.TEAM1) shouldBe "7"
        sevenLove.displayPoints(side = TeamSide.TEAM2) shouldBe "0"
        // No target is assumed — 7-0 sits there until the umpire marks the set (#911).
        sevenLove.gamesTeam1 shouldBe 0
        sevenLove.completedSets.shouldHaveSize(size = 0)
    }

    @Test
    fun `a tiebreak beyond seven keeps counting, since the engine assumes no target`() {
        val long =
            stateOf(
                events =
                    listOf(element = ScoreEvent.TiebreakStarted) +
                        points(side = TeamSide.TEAM1, times = 15) + points(side = TeamSide.TEAM2, times = 13),
            )
        long.displayPoints(side = TeamSide.TEAM1) shouldBe "15"
        long.displayPoints(side = TeamSide.TEAM2) shouldBe "13"
        long.completedSets.shouldHaveSize(size = 0)
    }

    @Test
    fun `awarding the set banks the tiebreak points onto it and leaves tiebreak mode`() {
        val decided =
            stateOf(
                events =
                    listOf(element = ScoreEvent.TiebreakStarted) +
                        points(side = TeamSide.TEAM1, times = 7) + points(side = TeamSide.TEAM2, times = 5) +
                        listOf(element = ScoreEvent.SetAwarded(side = TeamSide.TEAM1)),
            )
        val set = decided.completedSets.single()
        set.tiebreakTeam1Points shouldBe 7
        set.tiebreakTeam2Points shouldBe 5
        set.winner shouldBe TeamSide.TEAM1
        // Back to ordinary scoring for the next set.
        decided.isTiebreak shouldBe false
        decided.displayPoints(side = TeamSide.TEAM1) shouldBe "0"
    }

    @Test
    fun `a set won without a tiebreak carries no tiebreak points`() {
        val plain =
            stateOf(
                events =
                    (1..6).flatMap { points(side = TeamSide.TEAM1, times = 4) } +
                        listOf(element = ScoreEvent.SetAwarded(side = TeamSide.TEAM1)),
            )
        plain.completedSets.single().tiebreakTeam1Points shouldBe null
        plain.completedSets.single().tiebreakTeam2Points shouldBe null
    }

    @Test
    fun `sets accumulate in the order they were banked`() {
        val twoSets =
            stateOf(
                events =
                    (1..6).flatMap { points(side = TeamSide.TEAM1, times = 4) } +
                        listOf(element = ScoreEvent.SetAwarded(side = TeamSide.TEAM1)) +
                        // Set two must be started explicitly (#984): awarding a set parks the match
                        // between sets rather than rolling into the next.
                        listOf(element = ScoreEvent.SetStarted) +
                        (1..4).flatMap { points(side = TeamSide.TEAM2, times = 4) } +
                        listOf(element = ScoreEvent.SetAwarded(side = TeamSide.TEAM2)),
            )
        twoSets.completedSets.shouldHaveSize(size = 2)
        twoSets.completedSets[0].winner shouldBe TeamSide.TEAM1
        twoSets.completedSets[0].gamesTeam1 shouldBe 6
        twoSets.completedSets[1].winner shouldBe TeamSide.TEAM2
        twoSets.completedSets[1].gamesTeam2 shouldBe 4
    }

    @Test
    fun `the server is a player id, so doubles rotation is expressible`() {
        // The one place doubles differs: the serve rotates through four people, so an event naming a SIDE
        // could not express it. The ENGINE still rotates nothing — it knows no roster; since #985 the
        // service appends the rotation as an explicit event, which is what keeps undo able to reverse it.
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        val carol = UUID.randomUUID()
        val dave = UUID.randomUUID()

        var current = ScoreEngine.apply(state = ScoreState(), event = ScoreEvent.MatchStarted)
        listOf(alice, carol, bob, dave).forEach { server ->
            current = ScoreEngine.apply(state = current, event = ScoreEvent.ServerAssigned(playerId = server))
            current = stateFrom(state = current, events = points(side = TeamSide.TEAM1, times = 4))
        }
        current.serverId shouldBe dave
        current.gamesTeam1 shouldBe 4
    }

    @Test
    fun `winning a game does not change the server on its own`() {
        val alice = UUID.randomUUID()
        val afterGame =
            stateFrom(
                state = ScoreEngine.apply(state = ScoreState(), event = ScoreEvent.ServerAssigned(playerId = alice)),
                events = points(side = TeamSide.TEAM2, times = 4),
            )
        afterGame.serverId shouldBe alice
    }

    @Test
    fun `retirement hands the match to the opponent and records who conceded`() {
        val retired = state(ScoreEvent.Retired(side = TeamSide.TEAM2))
        retired.isFinished shouldBe true
        retired.outcome?.kind shouldBe LiveOutcomeKind.RETIRED
        retired.outcome?.winner shouldBe TeamSide.TEAM1
        retired.outcome?.concededBy shouldBe TeamSide.TEAM2
    }

    @Test
    fun `a default looks like a retirement but says so on the record`() {
        val defaulted = state(ScoreEvent.Defaulted(side = TeamSide.TEAM1))
        defaulted.outcome?.kind shouldBe LiveOutcomeKind.DEFAULTED
        defaulted.outcome?.winner shouldBe TeamSide.TEAM2
        defaulted.outcome?.concededBy shouldBe TeamSide.TEAM1
    }

    @Test
    fun `a match played out records no conceding side`() {
        val done = state(ScoreEvent.MatchAwarded(side = TeamSide.TEAM1))
        done.outcome?.kind shouldBe LiveOutcomeKind.COMPLETED
        done.outcome?.winner shouldBe TeamSide.TEAM1
        done.outcome?.concededBy shouldBe null
    }

    @Test
    fun `the score reached is preserved by a retirement, since the record needs it`() {
        // #911 §10 rates a retirement on the real score, so the partial set must survive the outcome.
        val retiredMidSet =
            stateOf(
                events =
                    (1..3).flatMap { points(side = TeamSide.TEAM1, times = 4) } +
                        points(side = TeamSide.TEAM2, times = 2) +
                        listOf(element = ScoreEvent.Retired(side = TeamSide.TEAM2)),
            )
        retiredMidSet.gamesTeam1 shouldBe 3
        retiredMidSet.pointsTeam2 shouldBe 2
        retiredMidSet.outcome?.winner shouldBe TeamSide.TEAM1
    }

    @Test
    fun `scoring after the match has ended is ignored`() {
        val ended = state(ScoreEvent.MatchAwarded(side = TeamSide.TEAM1))
        val stillEnded = ScoreEngine.apply(state = ended, event = ScoreEvent.PointWon(side = TeamSide.TEAM2))
        stillEnded shouldBe ended
    }

    @Test
    fun `the server can still be corrected after the match has ended`() {
        // Carved out of the ignore-everything rule: naming the server is a record correction, not scoring.
        val alice = UUID.randomUUID()
        val ended = state(ScoreEvent.MatchAwarded(side = TeamSide.TEAM1))
        ScoreEngine.apply(state = ended, event = ScoreEvent.ServerAssigned(playerId = alice)).serverId shouldBe alice
    }

    @Test
    fun `an official start is distinct from the first point`() {
        // The gap between opening the app and the players starting is what would otherwise corrupt a
        // duration figure, so the start is its own event rather than inferred from the first point.
        val fresh = ScoreState()
        fresh.hasStarted shouldBe false

        val started = state(ScoreEvent.MatchStarted)
        started.hasStarted shouldBe true
        started.pointsTeam1 shouldBe 0

        // The ENGINE stays permissive: an umpire who forgets to tap Start must not silently lose the
        // point, and the engine has no way to report a refusal. #986 requires the start, but enforces it
        // where it can say so — the service refuses the write and the UI disables the control.
        val unstarted =
            points(side = TeamSide.TEAM1, times = 1)
                .fold(initial = ScoreState()) { acc, event -> ScoreEngine.apply(state = acc, event = event) }
        unstarted.hasStarted shouldBe false
        unstarted.pointsTeam1 shouldBe 1
    }

    @Test
    fun `pause and resume toggle without touching the score`() {
        val played = points(side = TeamSide.TEAM1, times = 2)
        val paused = stateOf(events = played + listOf(element = ScoreEvent.Paused))
        paused.isPaused shouldBe true
        paused.displayPoints(side = TeamSide.TEAM1) shouldBe "30"

        val resumed = stateOf(events = played + listOf(ScoreEvent.Paused, ScoreEvent.Resumed))
        resumed.isPaused shouldBe false
        resumed.displayPoints(side = TeamSide.TEAM1) shouldBe "30"
    }

    @Test
    fun `scoring while paused still counts`() {
        // Deliberate: a forgotten Resumed is likelier than a real point during a rain delay, and dropping
        // the point is the worse failure. The flag stays set so a UI can prompt.
        val scoredWhilePaused =
            stateOf(
                events = listOf(element = ScoreEvent.Paused) + points(side = TeamSide.TEAM1, times = 1),
            )
        scoredWhilePaused.displayPoints(side = TeamSide.TEAM1) shouldBe "15"
        scoredWhilePaused.isPaused shouldBe true
    }

    @Test
    fun `starting clears a pause, so a restart needs no separate resume`() {
        val restarted = stateOf(events = listOf(ScoreEvent.MatchStarted, ScoreEvent.Paused, ScoreEvent.MatchStarted))
        restarted.isPaused shouldBe false
        restarted.hasStarted shouldBe true
    }

    @Test
    fun `a pause survives being folded across games and sets`() {
        val paused =
            stateOf(
                events =
                    listOf(ScoreEvent.MatchStarted, ScoreEvent.Paused) +
                        (1..6).flatMap { points(side = TeamSide.TEAM1, times = 4) } +
                        listOf(element = ScoreEvent.SetAwarded(side = TeamSide.TEAM1)),
            )
        // Nothing in game or set completion resets the pause — only Resumed or MatchStarted does.
        paused.isPaused shouldBe true
        paused.completedSets.shouldHaveSize(size = 1)
    }

    @Test
    fun `replay of a whole match folds to the same state as applying step by step`() {
        // replay is a fold over apply, not a second traversal — this is the assertion that they cannot
        // drift, which is the classic way event-sourced scoring goes wrong (#911 §7).
        // A realistic log: started, two sets with the second explicitly begun, then declared won.
        val events =
            listOf(element = ScoreEvent.MatchStarted) +
                (1..6).flatMap { points(side = TeamSide.TEAM1, times = 4) } +
                listOf(element = ScoreEvent.SetAwarded(side = TeamSide.TEAM1)) +
                listOf(element = ScoreEvent.SetStarted) +
                (1..6).flatMap { points(side = TeamSide.TEAM2, times = 4) } +
                listOf(element = ScoreEvent.SetAwarded(side = TeamSide.TEAM2)) +
                listOf(element = ScoreEvent.MatchAwarded(side = TeamSide.TEAM2))
        val log = events.mapIndexed { index, event -> LoggedAction.Scored(sequence = index + 1L, event = event) }

        // Folded raw, not via stateOf, since the log already carries its own MatchStarted.
        val stepByStep = events.fold(initial = ScoreState()) { acc, e -> ScoreEngine.apply(state = acc, event = e) }
        ScoreEngine.replay(log = log) shouldBe stepByStep
    }

    private fun stateFrom(
        state: ScoreState,
        events: List<ScoreEvent>,
    ): ScoreState = events.fold(initial = state) { acc, event -> ScoreEngine.apply(state = acc, event = event) }
}
