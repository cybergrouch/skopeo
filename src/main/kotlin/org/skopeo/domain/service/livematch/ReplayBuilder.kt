// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import org.skopeo.common.contract.ReplayDocument
import org.skopeo.common.contract.ReplayEvent
import org.skopeo.common.contract.ReplayPoint
import org.skopeo.common.contract.ReplayResult
import org.skopeo.common.contract.ReplayScore
import org.skopeo.common.contract.ReplaySet
import org.skopeo.common.dto.match.SetScoreRequest
import org.skopeo.domain.mapper.entity.livematch.kindOf
import org.skopeo.domain.mapper.entity.livematch.scoreEventOf
import org.skopeo.domain.mapper.entity.livematch.sideOf
import org.skopeo.domain.model.CompletedSet
import org.skopeo.domain.model.ScoreEvent
import org.skopeo.domain.model.ScoreState
import org.skopeo.domain.model.TeamSide

/** One surviving umpire action and when it happened, in milliseconds since the first one. */
data class TimedScoreEvent(
    val event: ScoreEvent,
    val atMs: Long,
)

/**
 * Builds a match's [ReplayDocument] from its undo-resolved actions (#1145). Pure, like [ScoreEngine], and
 * built on it: every step is [ScoreEngine.apply], so the replay cannot disagree with the score the umpire
 * saw.
 *
 * The same function serves recording at finalize, the backfill, and the upgrade-on-read of an older
 * document ([rebuild]) — there is one way to derive the timeline, so a regenerated document is the one a
 * fresh recording would have produced.
 */
object ReplayBuilder {
    /** The format this code writes. Bump only with an additive change; [rebuild] upgrades older ones. */
    const val CURRENT_VERSION = 1

    /** The replay of [events], or null when they never reach an ending — there is no result to replay to. */
    fun build(events: List<TimedScoreEvent>): ReplayDocument? {
        var state = ScoreState()
        val points = mutableListOf<ReplayPoint>()
        events.forEach { timed ->
            val after = ScoreEngine.apply(state = state, event = timed.event)
            // An action the engine ignored (scoring after the end) changed nothing and is not a step.
            if (after != state) {
                stepOf(before = state, after = after, timed = timed, index = points.size)?.let { points += it }
            }
            state = after
        }
        val outcome = state.outcome ?: return null
        return ReplayDocument(
            version = CURRENT_VERSION,
            result = ReplayResult(sets = recordableSets(state = state).map { it.toReplaySet() }, winner = outcome.winner.name),
            events = events.map { ReplayEvent(kind = kindOf(event = it.event), side = sideOf(event = it.event), atMs = it.atMs) },
            points = points,
        )
    }

    /** [document] regenerated from its own stored events, in the current format. */
    fun rebuild(document: ReplayDocument): ReplayDocument? =
        build(
            events =
                document.events.mapIndexed { index, event ->
                    TimedScoreEvent(
                        event = scoreEventOf(kind = event.kind, side = event.side, sequence = index.toLong()),
                        atMs = event.atMs,
                    )
                },
        )

    private fun stepOf(
        before: ScoreState,
        after: ScoreState,
        timed: TimedScoreEvent,
        index: Int,
    ): ReplayPoint? {
        val event = timed.event
        val (kind, wonBy) =
            when (event) {
                is ScoreEvent.PointWon -> KIND_POINT to event.side
                is ScoreEvent.GameAwarded -> KIND_GAME to event.side
                is ScoreEvent.SetAwarded -> KIND_SET to event.side
                is ScoreEvent.Ending -> KIND_END to (after.outcome?.winner ?: return null)
                else -> return null
            }
        val server = before.servingSide
        val setWon = after.completedSets.size > before.completedSets.size
        // A tiebreak is won by awarding the set, and counts as the set's deciding game.
        val gameWon = after.gamesPlayed() > before.gamesPlayed() || (setWon && before.isTiebreak)
        val breakable = server != null && !before.isTiebreak
        return ReplayPoint(
            index = index,
            kind = kind,
            wonBy = wonBy.name,
            server = server?.name,
            atMs = timed.atMs,
            setNumber = setNumberOf(before = before),
            tiebreak = before.isTiebreak,
            breakPoint = kind == KIND_POINT && breakable && before.receiverHasBreakPoint(server = server),
            gameWon = gameWon,
            breakOfServe = gameWon && breakable && wonBy != server,
            setWon = setWon,
            outcome = after.outcome?.kind?.name.takeIf { kind == KIND_END },
            score = after.toReplayScore(),
        )
    }

    /** The set a step belongs to. Between sets, that is the one just banked: an ending there closes it. */
    private fun setNumberOf(before: ScoreState): Int =
        if (before.isBetweenSets && before.completedSets.isNotEmpty()) before.completedSets.size else before.completedSets.size + 1

    private fun ScoreState.gamesPlayed(): Int = gamesTeam1 + gamesTeam2

    /** The receiver is one point from breaking: at least 40 and ahead, which covers 0-40 through AD-out. */
    private fun ScoreState.receiverHasBreakPoint(server: TeamSide?): Boolean {
        val serving = server ?: return false
        val receiving = points(side = serving.opponent())
        return receiving >= GAME_POINT_THRESHOLD && receiving > points(side = serving)
    }

    private fun ScoreState.toReplayScore(): ReplayScore =
        ReplayScore(
            sets = completedSets.map { it.toReplaySet() },
            gamesTeam1 = gamesTeam1,
            gamesTeam2 = gamesTeam2,
            pointsTeam1 = displayPoints(side = TeamSide.TEAM1),
            pointsTeam2 = displayPoints(side = TeamSide.TEAM2),
        )

    private fun CompletedSet.toReplaySet(): ReplaySet =
        ReplaySet(team1 = gamesTeam1, team2 = gamesTeam2, tiebreakTeam1 = tiebreakTeam1Points, tiebreakTeam2 = tiebreakTeam2Points)

    private fun SetScoreRequest.toReplaySet(): ReplaySet =
        ReplaySet(team1 = team1Games, team2 = team2Games, tiebreakTeam1 = tiebreakTeam1Points, tiebreakTeam2 = tiebreakTeam2Points)

    private const val KIND_POINT = "POINT"
    private const val KIND_GAME = "GAME"
    private const val KIND_SET = "SET"
    private const val KIND_END = "END"
    private const val GAME_POINT_THRESHOLD = 3
}
