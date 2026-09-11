// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skopeo.domain.model.LoggedAction
import org.skopeo.domain.model.ScoreEvent
import org.skopeo.domain.model.ScoreState
import org.skopeo.domain.model.TeamSide

/**
 * Undo, case by case — the list #911 names in its exit criteria, plus the invariant underneath them.
 *
 * Undo **appends a compensating marker; it never pops** (§6). Two things follow, and both are asserted in
 * every test here rather than in one place:
 *
 * 1. `effective(log)` is the only layer that knows undo exists — `apply` never sees one, so it stays a
 *    plain tennis step with no history awareness.
 * 2. **The log is left intact.** Nothing is deleted, ever. That is the whole point of an append-only log
 *    whose stated goal is to audit everything: it should still be able to say *the umpire corrected
 *    themselves here*. So each test checks the log's size and contents are unchanged as well as the score.
 */
class ScoreEngineUndoTest {
    /**
     * Build a log from actions already carrying their sequence numbers, on a **started** match.
     *
     * Scoring is inert until `MATCH_STARTED` (#986), so every log that expects points to count needs
     * it. Sequence 0 keeps it ahead of the numbered actions, which start at 1, so nothing here has to
     * renumber. The undo tests are about history resolution, not about the start gate.
     */
    private fun log(vararg actions: LoggedAction): List<LoggedAction> =
        listOf(element = scored(sequence = 0, event = ScoreEvent.MatchStarted)) + actions

    /** What [log] folds to with everything undone: started, nothing scored. */
    private val startedOnly = ScoreState(hasStarted = true)

    private fun scored(
        sequence: Long,
        event: ScoreEvent,
    ) = LoggedAction.Scored(sequence = sequence, event = event)

    private fun undo(
        sequence: Long,
        target: Long,
    ) = LoggedAction.Undone(sequence = sequence, targetSequence = target)

    private fun point(side: TeamSide) = ScoreEvent.PointWon(side = side)

    /** A log of [count] points to [side], numbered from 1. */
    private fun points(
        side: TeamSide,
        count: Int,
    ): List<LoggedAction> = (1..count).map { scored(sequence = it.toLong(), event = point(side = side)) }

    @Test
    fun `undo of a plain point takes the score back one`() {
        val history =
            log(
                scored(sequence = 1, event = point(side = TeamSide.TEAM1)),
                scored(sequence = 2, event = point(side = TeamSide.TEAM1)),
                undo(sequence = 3, target = 2),
            )

        val state = ScoreEngine.replay(log = history)
        state.displayPoints(side = TeamSide.TEAM1) shouldBe "15"
        state.pointsTeam1 shouldBe 1

        // The log still holds all three rows — the undone point among them.
        // +1 on each: `log` prepends the MATCH_STARTED that scoring now requires (#986).
        history.shouldHaveSize(size = 4)
        history.filterIsInstance<LoggedAction.Scored>().shouldHaveSize(size = 3)
    }

    @Test
    fun `undo of an undo is a redo`() {
        val history =
            log(
                scored(sequence = 1, event = point(side = TeamSide.TEAM1)),
                undo(sequence = 2, target = 1),
                undo(sequence = 3, target = 2),
            )

        // The second marker cancels the first, so the point counts again.
        // The surviving point, plus the prepended MATCH_STARTED.
        ScoreEngine.effective(log = history).shouldHaveSize(size = 2)
        ScoreEngine.replay(log = history).pointsTeam1 shouldBe 1
        history.shouldHaveSize(size = 4)
    }

    @Test
    fun `undo of an undo of an undo cancels again`() {
        // Three deep, to prove the resolution is genuinely recursive rather than a single toggle.
        val history =
            log(
                scored(sequence = 1, event = point(side = TeamSide.TEAM1)),
                undo(sequence = 2, target = 1),
                undo(sequence = 3, target = 2),
                undo(sequence = 4, target = 3),
            )
        // MATCH_STARTED always survives — only the scoring actions cancel.
        ScoreEngine.effective(log = history).shouldHaveSize(size = 1)
        ScoreEngine.replay(log = history) shouldBe startedOnly
    }

    @Test
    fun `undo across a game boundary reopens the game at 40-30`() {
        // Four points wins the game; undoing the fourth must put the game back, not just the point.
        val history =
            points(side = TeamSide.TEAM1, count = 3) +
                log(
                    scored(sequence = 4, event = point(side = TeamSide.TEAM2)),
                    scored(sequence = 5, event = point(side = TeamSide.TEAM2)),
                    scored(sequence = 6, event = point(side = TeamSide.TEAM1)),
                    undo(sequence = 7, target = 6),
                )

        val state = ScoreEngine.replay(log = history)
        state.gamesTeam1 shouldBe 0
        state.displayPoints(side = TeamSide.TEAM1) shouldBe "40"
        state.displayPoints(side = TeamSide.TEAM2) shouldBe "30"
    }

    @Test
    fun `undo across a set boundary unbanks the set and restores its games`() {
        val throughSet =
            (1..6).flatMap { game ->
                (1..4).map { point -> scored(sequence = (game - 1) * 4L + point, event = point(side = TeamSide.TEAM1)) }
            }
        // `log` is used once, at the front: calling it again mid-expression would prepend a second
        // MATCH_STARTED and two rows would share sequence 0.
        val withSet =
            log(*throughSet.toTypedArray()) +
                scored(sequence = 25, event = ScoreEvent.SetAwarded(side = TeamSide.TEAM1))

        ScoreEngine.replay(log = withSet).completedSets.shouldHaveSize(size = 1)

        val undone = withSet + undo(sequence = 26, target = 25)
        val state = ScoreEngine.replay(log = undone)
        state.completedSets.shouldHaveSize(size = 0)
        state.gamesTeam1 shouldBe 6
        // 24 points + the set + its undo, plus the prepended MATCH_STARTED.
        undone.shouldHaveSize(size = 27)
    }

    @Test
    fun `undo of a tiebreak point takes back the ordinal, not a game`() {
        val history =
            log(
                scored(sequence = 1, event = ScoreEvent.TiebreakStarted),
                scored(sequence = 2, event = point(side = TeamSide.TEAM1)),
                scored(sequence = 3, event = point(side = TeamSide.TEAM1)),
                scored(sequence = 4, event = point(side = TeamSide.TEAM2)),
                undo(sequence = 5, target = 3),
            )

        val state = ScoreEngine.replay(log = history)
        state.isTiebreak shouldBe true
        state.displayPoints(side = TeamSide.TEAM1) shouldBe "1"
        state.displayPoints(side = TeamSide.TEAM2) shouldBe "1"
        state.gamesTeam1 shouldBe 0
    }

    @Test
    fun `undoing the start of a tiebreak returns the game to ordinary scoring`() {
        val history =
            log(
                scored(sequence = 1, event = ScoreEvent.TiebreakStarted),
                scored(sequence = 2, event = point(side = TeamSide.TEAM1)),
                undo(sequence = 3, target = 1),
            )

        val state = ScoreEngine.replay(log = history)
        state.isTiebreak shouldBe false
        // The surviving point is now an ordinary one, so it renders as 15 rather than 1.
        state.displayPoints(side = TeamSide.TEAM1) shouldBe "15"
    }

    @Test
    fun `undo of the very first action returns to a fresh state`() {
        val history =
            log(
                scored(sequence = 1, event = point(side = TeamSide.TEAM1)),
                undo(sequence = 2, target = 1),
            )

        ScoreEngine.replay(log = history) shouldBe startedOnly
        // Only the prepended MATCH_STARTED survives; the point and its undo cancel.
        ScoreEngine.effective(log = history).shouldHaveSize(size = 1)
        history.shouldHaveSize(size = 3)
    }

    @Test
    fun `undo when there is nothing to undo is inert`() {
        // An empty log, and a marker pointing at a sequence that was never written. Neither throws.
        // Target 999, not 0: sequence 0 is the prepended MATCH_STARTED, so aiming at it would un-start
        // the match and test something else entirely.
        ScoreEngine.replay(log = log(undo(sequence = 1, target = 999))) shouldBe startedOnly
        ScoreEngine.replay(log = emptyList()) shouldBe ScoreState()

        val stray =
            log(
                scored(sequence = 1, event = point(side = TeamSide.TEAM1)),
                undo(sequence = 2, target = 99),
            )
        ScoreEngine.replay(log = stray).pointsTeam1 shouldBe 1
    }

    @Test
    fun `undoing the same action twice does not double-count`() {
        val history =
            log(
                scored(sequence = 1, event = point(side = TeamSide.TEAM1)),
                scored(sequence = 2, event = point(side = TeamSide.TEAM1)),
                undo(sequence = 3, target = 2),
                undo(sequence = 4, target = 2),
            )
        // Cancelled is cancelled; a second marker aimed at it is not a redo.
        ScoreEngine.replay(log = history).pointsTeam1 shouldBe 1
    }

    @Test
    fun `undo of a retirement puts the match back in play`() {
        // The concluding event is undoable like any other, which is what lets a mis-tapped retirement be
        // corrected — and is why `apply` ignoring events after an outcome is safe rather than a trap.
        val history =
            log(
                scored(sequence = 1, event = point(side = TeamSide.TEAM1)),
                scored(sequence = 2, event = ScoreEvent.Retired(side = TeamSide.TEAM2)),
                undo(sequence = 3, target = 2),
                scored(sequence = 4, event = point(side = TeamSide.TEAM1)),
            )

        val state = ScoreEngine.replay(log = history)
        state.isFinished shouldBe false
        state.outcome shouldBe null
        // The point after the undone retirement counts, which it would not have while the match was over.
        state.displayPoints(side = TeamSide.TEAM1) shouldBe "30"
    }

    @Test
    fun `undo of a middle action leaves later actions in force`() {
        // Not last-in-first-out: a marker names its target, so the umpire can strike out an earlier action
        // and everything after it still counts.
        val history =
            log(
                scored(sequence = 1, event = point(side = TeamSide.TEAM1)),
                scored(sequence = 2, event = point(side = TeamSide.TEAM2)),
                scored(sequence = 3, event = point(side = TeamSide.TEAM1)),
                undo(sequence = 4, target = 2),
            )

        val state = ScoreEngine.replay(log = history)
        state.displayPoints(side = TeamSide.TEAM1) shouldBe "30"
        state.displayPoints(side = TeamSide.TEAM2) shouldBe "0"
    }

    @Test
    fun `apply is never handed an undo marker`() {
        // The structural guarantee behind all of the above: `effective` returns ScoreEvents, and there is
        // no undo member in that type — so it is impossible to pass one to `apply`. Asserted as the log
        // shrinking to its scoring actions, since the type system already makes the stronger claim.
        val history =
            log(
                scored(sequence = 1, event = point(side = TeamSide.TEAM1)),
                undo(sequence = 2, target = 1),
                scored(sequence = 3, event = point(side = TeamSide.TEAM2)),
            )
        ScoreEngine.effective(log = history) shouldBe listOf(ScoreEvent.MatchStarted, point(side = TeamSide.TEAM2))
    }

    @Test
    fun `a log handed over out of order is resolved by sequence, not by position`() {
        // Two Cloud Run instances can persist concurrently, so a read is ordered by the sequence column
        // rather than by arrival. Replaying an out-of-order list must give the same answer.
        val inOrder =
            log(
                scored(sequence = 1, event = point(side = TeamSide.TEAM1)),
                scored(sequence = 2, event = point(side = TeamSide.TEAM2)),
                undo(sequence = 3, target = 1),
            )
        val shuffled =
            log(
                undo(sequence = 3, target = 1),
                scored(sequence = 2, event = point(side = TeamSide.TEAM2)),
                scored(sequence = 1, event = point(side = TeamSide.TEAM1)),
            )
        ScoreEngine.replay(log = shuffled) shouldBe ScoreEngine.replay(log = inOrder)
        ScoreEngine.replay(log = shuffled).displayPoints(side = TeamSide.TEAM2) shouldBe "15"
    }
}
