// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.model

import java.util.UUID

/**
 * Which side of a live match an action belongs to (#911).
 *
 * **Never "left" or "right".** Switching sides in the umpire view is a display preference and is
 * deliberately not recorded, so a log written in terms of screen position would be corrupted the moment
 * the players change ends — see `docs/engineering/architecture/LIVE_MATCH.md` §6. Naming the *side of
 * the match* keeps the log meaningful regardless of how it is being displayed.
 *
 * A side, not a player: a point is won by a team, in singles and doubles alike. The one thing that names
 * an individual is [ScoreEvent.ServerAssigned], because in doubles the serve rotates through four people.
 */
enum class TeamSide {
    TEAM1,
    TEAM2,
    ;

    /** The other side. Used wherever conceding decides a winner by elimination (retirement, default). */
    fun opponent(): TeamSide = if (this == TEAM1) TEAM2 else TEAM1
}

/**
 * One thing the umpire did (#911). The alphabet of the append-only log.
 *
 * The engine is **permissive and the umpire is authoritative**: it advances points and closes a *game* by
 * the ordinary deuce/advantage rule, and everything above that — the set, the tiebreak, the match — is
 * declared rather than inferred. That is a deliberate boundary, not an omission. Sets vary (short sets,
 * pro sets, match tiebreaks), #911 requires that a set be endable below six games and that the UI not
 * presume a tiebreak target, and encoding every format variant is where tennis scoring implementations go
 * to die.
 *
 * There is no `Undo` member here on purpose. Undo is a property of the *log*, not a scoring operation —
 * it is resolved by [org.skopeo.domain.service.livematch.ScoreEngine.effective] before the fold, so
 * `apply` never sees one. See [LoggedAction.Undone].
 */
sealed interface ScoreEvent {
    /** A point to [side]. In a tiebreak this is an ordinal tick; otherwise it may close the game. */
    data class PointWon(val side: TeamSide) : ScoreEvent

    /**
     * The umpire hands [side] the game outright, whatever the points. The override for a scoring slip or
     * a point-penalty game, and the reason `apply` never needs to be talked out of its own arithmetic.
     */
    data class GameAwarded(val side: TeamSide) : ScoreEvent

    /**
     * The umpire declares the set won by [side] and banks it, at whatever the score is — 6-4, 7-6, or 3-2
     * because daylight ran out. Also how a tiebreak ends: #911 requires that the umpire, not the UI, marks
     * who won it, so the tiebreak points ride along onto the completed set rather than deciding it.
     */
    data class SetAwarded(val side: TeamSide) : ScoreEvent

    /**
     * The next set begins (#984).
     *
     * Separate from [SetAwarded] on purpose. Banking a set and starting the following one used to be
     * one step, which meant the match was already mid-next-set the instant a set ended — so the moment
     * to ask "is this over?" never existed, and a match played to a normal finish could never be
     * finalized. Splitting them creates that moment.
     *
     * Two events rather than one also lets undo reverse them independently: an umpire who starts the
     * next set by mistake can take it back without un-awarding the set that ended.
     */
    data object SetStarted : ScoreEvent

    /** Points become plain ordinals from here until the set is awarded. No target is assumed. */
    data object TiebreakStarted : ScoreEvent

    /**
     * Who is serving. A **player**, not a side — the one place doubles differs, since the serve rotates
     * through four people.
     *
     * The service appends one of these automatically when a game is awarded (#985), advancing through
     * the roster. It stays an explicit logged event rather than something the engine derives, for two
     * reasons: the engine knows nothing about players, and an appended event means undo reverses a
     * rotation for free. An umpire correcting the order just appends another.
     */
    data class ServerAssigned(val playerId: UUID) : ScoreEvent

    /** [side] retired. The opponent wins the match; the score reached stands as the record. */
    data class Retired(val side: TeamSide) : ScoreEvent

    /** [side] was defaulted. Same shape as a retirement, different reason, and the record says which. */
    data class Defaulted(val side: TeamSide) : ScoreEvent

    /** The umpire declares the match won by [side] — the ordinary end of a match that was played out. */
    data class MatchAwarded(val side: TeamSide) : ScoreEvent

    /**
     * Play has officially begun (#911).
     *
     * Distinct from the first point: the gap between "the umpire opened the app" and "the players
     * started" is exactly the interval that would otherwise corrupt a match-duration figure. Every log
     * row is timestamped, so this is the anchor those timings are measured from.
     */
    data object MatchStarted : ScoreEvent

    /**
     * Play is suspended — rain, darkness, a court handover — without ending the match.
     *
     * The reason this exists rather than a staleness timeout: a paused match may resume **days** later
     * on a court the host cannot yet book, so no timeout could tell "abandoned" from "waiting for
     * weather" without being wrong in one direction. Saying so explicitly is the only honest answer, and
     * it is what lets the log be kept until finalize rather than swept.
     */
    data object Paused : ScoreEvent

    /** Play resumes after a [Paused]. The gap between the two is a rain delay, not playing time. */
    data object Resumed : ScoreEvent
}

/**
 * An entry in the append-only log (#911, §6): either something that happened, or a marker cancelling
 * something that happened.
 *
 * [sequence] is the per-match monotonic number carried by a unique constraint in the database — the same
 * shape `match_number` uses (#898). It is what makes concurrent umpire writes from two Cloud Run instances
 * impossible to interleave, and it is also the identity an [Undone] marker points at.
 */
sealed interface LoggedAction {
    val sequence: Long

    /** A real scoring action. */
    data class Scored(override val sequence: Long, val event: ScoreEvent) : LoggedAction

    /**
     * The umpire took back the action at [targetSequence].
     *
     * **Appends; it does not pop.** The stated goal of #911 is to audit everything, and popping is the one
     * operation that defeats that — the log should still be able to say *the umpire corrected themselves
     * here*. Cancelling an [Undone] is therefore a redo, and cancelling a sequence that does not exist (or
     * is already cancelled) is simply inert, which is what "undo when there is nothing to undo" should do.
     */
    data class Undone(override val sequence: Long, val targetSequence: Long) : LoggedAction
}

/** How a set finished, banked at the moment [ScoreEvent.SetAwarded] is applied. */
data class CompletedSet(
    val gamesTeam1: Int,
    val gamesTeam2: Int,
    val winner: TeamSide,
    val tiebreakTeam1Points: Int? = null,
    val tiebreakTeam2Points: Int? = null,
)

/** Why a live match stopped. The record distinguishes them; #911 §10 decides what each means for ratings. */
enum class LiveOutcomeKind { COMPLETED, RETIRED, DEFAULTED }

/**
 * The end of a live match: who won, and how it came to an end.
 *
 * [concededBy] is null for a match that was played out, and names the retiring or defaulted side
 * otherwise. Keeping it distinct from [winner] is what lets the record say "won by retirement" rather
 * than silently presenting a walkover as an ordinary win.
 */
data class LiveOutcome(
    val kind: LiveOutcomeKind,
    val winner: TeamSide,
    val concededBy: TeamSide? = null,
)

/**
 * The current score — everything a scoreboard needs, and nothing else (#911 §7).
 *
 * **This is not history.** The stack lives in the log; `effective` resolves undo before the fold, so the
 * state never has to know that undo exists. It is deliberately close to the spectator payload, because the
 * document broadcast to spectators is a projection of exactly this.
 *
 * Two rules the design doc is emphatic about, both easy to violate later "for performance": do not cache
 * this between requests (two Cloud Run instances would diverge), and do not read the broadcast document
 * back to obtain it (that would make an outbound projection an authority it is not). Every write is
 * replay → apply → persist → project. A match is a few hundred events, so there is nothing to optimize.
 */
data class ScoreState(
    val pointsTeam1: Int = 0,
    val pointsTeam2: Int = 0,
    val gamesTeam1: Int = 0,
    val gamesTeam2: Int = 0,
    val completedSets: List<CompletedSet> = emptyList(),
    val serverId: UUID? = null,
    val isTiebreak: Boolean = false,
    val hasStarted: Boolean = false,
    val isPaused: Boolean = false,
    // A set has just been banked and the next one has not begun (#984). Scoring is inert here; the
    // umpire either starts the next set or finalizes. This is the "is this over?" moment that did not
    // exist when awarding a set rolled straight into the next.
    val isBetweenSets: Boolean = false,
    val outcome: LiveOutcome? = null,
) {
    /** Whether the match has ended, however it ended. */
    val isFinished: Boolean get() = outcome != null

    /**
     * Whether scoring actions apply right now (#984/#985/#986).
     *
     * Three states forbid them, each a deliberate pause rather than an error: before the match has
     * started (#986), between sets (#984), and after it has finished.
     *
     * Enforced by the service and the UI, not by the engine — the engine has no error channel, so
     * gating there would silently discard an umpire's tap. This property is what they gate on.
     */
    val isScorable: Boolean get() = hasStarted && !isBetweenSets && !isFinished

    /** Games in the current (unbanked) set, for [side]. */
    fun games(side: TeamSide): Int = if (side == TeamSide.TEAM1) gamesTeam1 else gamesTeam2

    /** Points in the current game, for [side]. Raw counts — [displayPoints] renders them as tennis scores. */
    fun points(side: TeamSide): Int = if (side == TeamSide.TEAM1) pointsTeam1 else pointsTeam2

    /**
     * The scoreboard rendering of the current game: `0 / 15 / 30 / 40 / AD` in a normal game, and the raw
     * ordinal in a tiebreak (#911 makes tiebreak scores plain numbers).
     *
     * Deuce is `40-40` and advantage is `AD-40`, derived from the raw counts rather than tracked as their
     * own states — so a game that has been through deuce nine times still renders correctly, and there is
     * no separate "deuce flag" to keep in step with the numbers.
     */
    fun displayPoints(side: TeamSide): String {
        val own = points(side = side)
        val other = points(side = side.opponent())
        return when {
            isTiebreak -> own.toString()
            // Deuce/advantage territory needs BOTH sides at 40 — otherwise 40-0 would read as "AD". In
            // this branch `own` cannot exceed 3: reaching 4 with the opponent below 3 is two clear, so
            // the game would already have closed and the points reset.
            own < ADVANTAGE_THRESHOLD || other < ADVANTAGE_THRESHOLD -> POINT_NAMES[own]
            own > other -> ADVANTAGE
            // Level, or trailing the advantage: both read 40.
            else -> DEUCE
        }
    }

    private companion object {
        val POINT_NAMES = listOf("0", "15", "30", "40")
        const val ADVANTAGE_THRESHOLD = 3
        const val DEUCE = "40"
        const val ADVANTAGE = "AD"
    }
}

/**
 * What a caller sees of a live match: the score, who is scoring it, and how far the log has got.
 *
 * [sequence] is what makes a stale read detectable — a client holding a lower number knows it is behind,
 * and it is the same value the spectator projection carries so a late-arriving broadcast cannot overwrite
 * a newer one (`LIVE_MATCH.md` §6).
 */
data class LiveMatchView(
    val matchId: UUID,
    val state: ScoreState,
    val sequence: Long,
    val scorerId: UUID? = null,
)
