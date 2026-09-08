// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import org.skopeo.domain.model.CompletedSet
import org.skopeo.domain.model.LiveOutcome
import org.skopeo.domain.model.LiveOutcomeKind
import org.skopeo.domain.model.LoggedAction
import org.skopeo.domain.model.ScoreEvent
import org.skopeo.domain.model.ScoreState
import org.skopeo.domain.model.TeamSide

/**
 * The live-scoring rules (#911), as pure functions with no I/O.
 *
 * Mirrors [org.skopeo.domain.service.calculator.RankingCalculator]: no side effects, no database, no
 * clock — which is why deuce, advantage, tiebreak and set completion can be tested exhaustively without
 * mocking anything.
 *
 * Three functions, and only one of them holds the rules:
 *
 * ```
 * log: List<LoggedAction>      the stack — persisted, append-only, never popped
 *   |
 *   +-- effective(log)         the ONLY layer that knows UNDO exists
 *   |      |
 *   |      +-- fold with apply(state, event)     sees real scoring events only
 *   |
 *   +-- ScoreState             the current score. No history, by design.
 * ```
 *
 * [replay] is what a caller actually uses; [apply] is where the tennis lives. Keeping `replay` a fold over
 * `apply` rather than its own traversal is the whole point: one implementation of the rules, so the live
 * path and the replay path cannot drift — the classic way event-sourced scoring goes wrong.
 *
 * **Permissive by design.** It advances points and closes a game; the set, the tiebreak and the match are
 * *declared* by the umpire. So it will happily accept a 7-0 tiebreak nobody closed out, or a set banked at
 * 2-1. That is the requirement, not a gap: #911 asks that the UI not presume a tiebreak target and that a
 * set be endable below six games.
 */
object ScoreEngine {
    /**
     * The score after [event]. The step function — every scoring rule is here and nowhere else.
     *
     * Never receives an undo: [effective] resolves those before the fold, so this stays a plain tennis
     * step with no history awareness. "Un-applying" a point would need to know the state *before* it,
     * which a step function does not have — that asymmetry is the reason undo is resolved in the log.
     *
     * A **finished** match ignores further scoring, with [ScoreEvent.ServerAssigned] carved out as a
     * record correction. A **paused** one does not: the umpire is authoritative, a forgotten
     * [ScoreEvent.Resumed] is far likelier than a deliberate point during a rain delay, and dropping the
     * point would be the worse failure. The pause is recorded in the state; a UI is free to prompt.
     */
    fun apply(
        state: ScoreState,
        event: ScoreEvent,
    ): ScoreState {
        // A finished match ignores further scoring. Not a rejection — the engine has no way to report one
        // and the umpire is authoritative — but banking points onto a concluded match would be nonsense,
        // and an undo of the concluding event restores the ability to score by removing it from the fold.
        if (state.isFinished && event !is ScoreEvent.ServerAssigned) return state
        return when (event) {
            is ScoreEvent.PointWon -> pointWon(state = state, side = event.side)
            is ScoreEvent.GameAwarded -> gameTo(state = state, side = event.side)
            is ScoreEvent.SetAwarded -> setTo(state = state, side = event.side)
            is ScoreEvent.TiebreakStarted -> state.copy(isTiebreak = true, pointsTeam1 = 0, pointsTeam2 = 0)
            is ScoreEvent.ServerAssigned -> state.copy(serverId = event.playerId)
            is ScoreEvent.Retired ->
                state.copy(
                    outcome =
                        LiveOutcome(
                            kind = LiveOutcomeKind.RETIRED,
                            winner = event.side.opponent(),
                            concededBy = event.side,
                        ),
                )
            is ScoreEvent.Defaulted ->
                state.copy(
                    outcome =
                        LiveOutcome(
                            kind = LiveOutcomeKind.DEFAULTED,
                            winner = event.side.opponent(),
                            concededBy = event.side,
                        ),
                )
            is ScoreEvent.MatchAwarded ->
                state.copy(outcome = LiveOutcome(kind = LiveOutcomeKind.COMPLETED, winner = event.side))
            // Starting also clears a pause, so a restart after a suspension needs no separate Resumed.
            is ScoreEvent.MatchStarted -> state.copy(hasStarted = true, isPaused = false)
            is ScoreEvent.Paused -> state.copy(isPaused = true)
            is ScoreEvent.Resumed -> state.copy(isPaused = false)
        }
    }

    /**
     * The scoring events that still count, in order — the log with every cancelled action and every undo
     * marker removed.
     *
     * The **only** place undo is understood. A marker is in force unless it has itself been cancelled,
     * which is what makes an undo of an undo a redo:
     *
     * ```
     * 1 PointWon   2 Undo(1)   3 Undo(2)      ->  effective = [PointWon]
     * ```
     *
     * Resolved by walking sequences in *descending* order. A marker always has a higher sequence than its
     * target, so by the time an action is examined every marker aimed at it has already been settled — one
     * pass, no fixpoint iteration. Cancelling a sequence that does not exist, or one already cancelled, is
     * inert, which is exactly what "undo when there is nothing to undo" should do.
     */
    fun effective(log: List<LoggedAction>): List<ScoreEvent> = surviving(log = log).map { it.event }

    /**
     * [effective], but keeping each action's sequence.
     *
     * Callers that need to *name* an action — an undo has to say which sequence it cancels — must use
     * this rather than matching on the event value. Two identical `PointWon(TEAM1)` rows are equal, so a
     * value match cannot tell a cancelled one from a surviving one.
     */
    fun surviving(log: List<LoggedAction>): List<LoggedAction.Scored> {
        val undosByTarget = log.filterIsInstance<LoggedAction.Undone>().groupBy { it.targetSequence }
        val cancelled = mutableSetOf<Long>()
        log.sortedByDescending { it.sequence }.forEach { action ->
            val aimedAtIt = undosByTarget[action.sequence].orEmpty()
            if (aimedAtIt.any { it.sequence !in cancelled }) cancelled += action.sequence
        }
        return log
            .sortedBy { it.sequence }
            .filterIsInstance<LoggedAction.Scored>()
            .filter { it.sequence !in cancelled }
    }

    /**
     * The score the whole log adds up to. What the server calls on every write.
     *
     * A fold, deliberately — see the class note. No snapshotting: a match is a few hundred events, so
     * replaying all of them costs microseconds, and a snapshot would only add a second thing that can go
     * stale.
     */
    fun replay(
        log: List<LoggedAction>,
        initial: ScoreState = ScoreState(),
    ): ScoreState = effective(log = log).fold(initial = initial) { state, event -> apply(state = state, event = event) }

    /**
     * A point to [side]: tick the count, and close the game if the ordinary rule says so.
     *
     * In a tiebreak the count just rises — no target is assumed, because #911 requires the umpire rather
     * than the UI to decide when a tiebreak is won.
     */
    private fun pointWon(
        state: ScoreState,
        side: TeamSide,
    ): ScoreState {
        val scored =
            if (side == TeamSide.TEAM1) {
                state.copy(pointsTeam1 = state.pointsTeam1 + 1)
            } else {
                state.copy(pointsTeam2 = state.pointsTeam2 + 1)
            }
        if (scored.isTiebreak) return scored
        val own = scored.points(side = side)
        val other = scored.points(side = side.opponent())
        // Four points and two clear. Expressed as the general rule rather than as cases, so a game that
        // went through deuce a dozen times needs no special handling — 8-6 closes exactly like 4-2.
        return if (own >= POINTS_TO_WIN_GAME && own - other >= CLEAR_BY) gameTo(state = scored, side = side) else scored
    }

    /** Bank a game to [side] and start the next one. Does not end the set — that is the umpire's call. */
    private fun gameTo(
        state: ScoreState,
        side: TeamSide,
    ): ScoreState {
        val withGame =
            if (side == TeamSide.TEAM1) {
                state.copy(gamesTeam1 = state.gamesTeam1 + 1)
            } else {
                state.copy(gamesTeam2 = state.gamesTeam2 + 1)
            }
        return withGame.copy(pointsTeam1 = 0, pointsTeam2 = 0)
    }

    /**
     * Bank the current set to [side] and start the next one.
     *
     * A tiebreak's points ride onto the completed set rather than deciding it, which is both what #911
     * asks for and what `MatchSetResult.tiebreakTeam1Points` already expects at finalize.
     */
    private fun setTo(
        state: ScoreState,
        side: TeamSide,
    ): ScoreState {
        val banked =
            CompletedSet(
                gamesTeam1 = state.gamesTeam1,
                gamesTeam2 = state.gamesTeam2,
                winner = side,
                tiebreakTeam1Points = state.pointsTeam1.takeIf { state.isTiebreak },
                tiebreakTeam2Points = state.pointsTeam2.takeIf { state.isTiebreak },
            )
        return state.copy(
            completedSets = state.completedSets + banked,
            gamesTeam1 = 0,
            gamesTeam2 = 0,
            pointsTeam1 = 0,
            pointsTeam2 = 0,
            isTiebreak = false,
        )
    }

    private const val POINTS_TO_WIN_GAME = 4
    private const val CLEAR_BY = 2
}
