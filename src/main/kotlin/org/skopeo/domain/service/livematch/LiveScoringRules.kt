// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.SCORING_ROLES
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.MatchStatus
import org.skopeo.domain.model.ScoreEvent
import org.skopeo.domain.model.ScoreState
import org.skopeo.domain.model.User
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import java.util.UUID

// The rules and lookups behind live scoring, kept out of LiveMatchService.
//
// Split off because both the class and its file sat at detekt's function limit once #984/#985/#986
// added the state-machine guards — and because none of this needs the service: each function takes
// what it uses. The limit noticing that is the limit working.

/**
 * Whether [this] is a scoring action, as opposed to a transition (#984/#986).
 *
 * The transitions — starting the match, starting the next set, pausing, resuming, and the three ways to
 * end one — must stay available whatever state the match is in, or it could never leave that state.
 * Only the actions that advance a score are gated.
 *
 * [ScoreEvent.GameStarted] joins them (#1083) even though it reads like a transition, and the test is
 * the rule above rather than the name: no state *needs* it to be left — a set can always be awarded,
 * retired or defaulted out of — so gating it strands nobody, and it is meaningless anywhere but inside
 * a set with no game already open.
 */
internal fun ScoreEvent.isScoringAction(): Boolean =
    when (this) {
        is ScoreEvent.PointWon,
        is ScoreEvent.GameAwarded,
        is ScoreEvent.SetAwarded,
        is ScoreEvent.TiebreakStarted,
        is ScoreEvent.GameStarted,
        -> true
        else -> false
    }

/**
 * Whether a scoring action may be recorded against [state] (#984/#985/#986).
 *
 * These rules live at the service boundary rather than in `ScoreEngine` deliberately: the engine has no
 * error channel, so enforcing them there would make it *silently discard* an umpire's tap, and a lost
 * point is a worse failure than a mis-sequenced one. Refusing with a reason is what lets the view say
 * why a control did nothing — the same placement as #952's already-recorded-result guard.
 *
 * File-level because it needs only its arguments; `LiveMatchService` is at detekt's function limit, and
 * a class that keeps absorbing rules is what that limit exists to notice.
 */
internal fun scoringAllowed(
    state: ScoreState,
    event: ScoreEvent,
): Either<ServiceError, Unit> =
    either {
        ensure(condition = state.hasStarted) {
            ServiceError.Conflict(message = "This match has not been started. Press Start match before scoring.")
        }
        ensure(condition = !state.isBetweenSets) {
            ServiceError.Conflict(
                message = "That set has ended. Start the next set, or finalize the match, before scoring again.",
            )
        }
        // A point with nobody serving leaves the scoreboard unable to say who is, and means the umpire
        // skipped a step. Games and sets are umpire declarations and need no server.
        ensure(condition = event !is ScoreEvent.PointWon || state.serverId != null) {
            ServiceError.Conflict(message = "Nobody is serving yet. Set who is serving before recording a point.")
        }
        gameLevelAllowed(state = state, event = event)
    }

/**
 * The game-level rules (#1083): which of the four scoring actions applies between games, and which
 * inside one.
 *
 * Split out because `scoringAllowed` was at detekt's complexity limit with three rules, not seven —
 * and because these four share one subject where the others share none. Every message names the action
 * that WOULD work, since #1070's whole finding was that a refusal without a next step reads as a bug.
 */
private fun Raise<ServiceError>.gameLevelAllowed(
    state: ScoreState,
    event: ScoreEvent,
) {
    when (event) {
        // A point needs somewhere to go. Between games there is nowhere, and silently banking it into
        // the next game is exactly the quiet wrongness this state exists to prevent.
        is ScoreEvent.PointWon ->
            ensure(condition = state.isPointable) {
                ServiceError.Conflict(message = "No game is under way. Press Start game, or start a tiebreak, before scoring a point.")
            }
        // Awarding a game that never started would bank a game the log has no points for.
        is ScoreEvent.GameAwarded ->
            ensure(condition = state.isInGame) {
                ServiceError.Conflict(message = "No game is under way. Press Start game before awarding a game.")
            }
        // Award the game first (#1083). This is the mis-tap that used to end a set mid-rally, and the
        // game in progress would have been lost rather than banked.
        is ScoreEvent.SetAwarded ->
            ensure(condition = !state.isInGame) {
                ServiceError.Conflict(message = "A game is under way. Award that game before ending the set.")
            }
        is ScoreEvent.GameStarted -> {
            ensure(condition = !state.isInGame) {
                ServiceError.Conflict(message = "A game is already under way.")
            }
            ensure(condition = !state.isTiebreak) {
                ServiceError.Conflict(message = "A tiebreak is under way. End the set to finish it.")
            }
        }
        is ScoreEvent.TiebreakStarted -> {
            ensure(condition = !state.isInGame) {
                ServiceError.Conflict(message = "A game is under way. Award that game before starting a tiebreak.")
            }
            // Tiebreaks are played from a level score -- 6-6 ordinarily, 5-5 and below in shortened
            // formats, and 0-0 for a deciding-set match tiebreak. Level, not even-numbered: 5-5
            // qualifies and 6-4 does not. The engine stays permissive about the TARGET (#911 asks that
            // no target be presumed); this is about when a tiebreak can begin at all.
            ensure(condition = state.gamesTeam1 == state.gamesTeam2) {
                ServiceError.Conflict(
                    message = "A tiebreak needs the games level, and they are ${state.gamesTeam1}-${state.gamesTeam2}.",
                )
            }
        }
        else -> Unit
    }
}

/** The caller, if they may score at all. A flat capability check — see the class note. */
internal fun scorerOf(
    users: UserRepository,
    token: VerifiedFirebaseToken,
): Either<ServiceError, User> {
    val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
    return if (caller == null || caller.capabilities.none { it in SCORING_ROLES }) {
        ServiceError.Forbidden().left()
    } else {
        caller.right()
    }
}

/**
 * The match, if it can still be scored live.
 *
 * A rated match is frozen for the same reason `uploadResult` refuses one: the result has already fed
 * ratings and points, and re-deriving it from a live log would silently diverge from what was paid.
 */
internal fun scorableMatchOf(
    matches: MatchRepository,
    matchId: UUID,
) = either {
    val match = matches.findById(matchId = matchId).bind().toDomain()
    ensure(condition = match.isActive) { ServiceError.Conflict(message = "Match is disabled") }
    ensure(condition = match.ratedAt == null) {
        ServiceError.Conflict(message = "Cannot score a match that has already been rated")
    }
    // Finalize ends live scoring (#952). `ratedAt` was the only guard here, and it is the wrong
    // line: rating happens when the EVENT is finalized (#403), which can be days after the match
    // was recorded. In between, a match with a full scoreline could still be claimed and scored.
    //
    // Refused rather than allowed-with-care, because the alternative is two ways to change one
    // recorded result. The live log is working state (§8a) and the match is the record;
    // corrections go through the ordinary result-editing path that everything else uses. Two
    // routes to the same edit is how they drift.
    //
    // Note this guard is safe for finalize itself: it runs BEFORE uploadResult, when the match
    // is still SCHEDULED or IN_PROGRESS. Reading the score is unaffected — `scoreboard` does not
    // come through here, so a finished match's scoreboard stays visible.
    ensure(condition = match.status != MatchStatus.COMPLETED) {
        ServiceError.Conflict(
            message =
                "This match already has a recorded result. Live scoring is finished; correct the " +
                    "score through the match result instead.",
        )
    }
    match
}

/**
 * How many games have been played in total, banked sets included (#985).
 *
 * Monotonic across a set boundary, which a per-set count is not: awarding a set zeroes the current
 * games, so comparing those alone would read a completed set as a game *un*-played. Used to notice that
 * a game finished, however it finished.
 */
internal fun gamesPlayed(state: ScoreState): Int =
    state.completedSets.sumOf { it.gamesTeam1 + it.gamesTeam2 } + state.gamesTeam1 + state.gamesTeam2

/**
 * Whether the serve changes hands as a result of the action just recorded (#985, #1097).
 *
 * Three transitions, and they have to be told apart because [gamesPlayed] rises for two of them:
 *
 * - **a game completed** in ordinary play — always hands the serve on, however the game ended
 * - **a point was scored in a tiebreak** — hands it on when the running total turns odd, which is the
 *   whole 1-2-2-2 sequence: the first server owes one point, everyone after owes two
 * - **a tiebreak was banked as a set** — hands it on only when the tiebreak's own handovers came out
 *   even, because the next set must open on the opposite side to whoever served the tiebreak's first
 *   point, and that needs the *total* number of handovers to be odd
 *
 * The third is why this can no longer be the single [gamesPlayed] comparison it was before #1097.
 * Banking a tiebreak raises [gamesPlayed], so it used to rotate unconditionally — which was correct
 * only because nothing rotated *during* a tiebreak. Add the second case and a 7-3 tiebreak (five
 * handovers, already sitting on the right side) gets rotated once too often:
 *
 * ```
 * 7-3  (10 points)  handovers after 1,3,5,7,9    = 5 (odd)   already correct -> do not rotate
 * 7-5  (12 points)  handovers after 1,3,5,7,9,11 = 6 (even)  one short       -> rotate
 * ```
 *
 * Handover *timing* comes from the score, but the action at each one is a plain **toggle**, and that is
 * what lets an umpire's manual correction stick: the pairing boundaries are fixed by the point count
 * and cannot be wrong, so the only thing a correction has to fix is which side is on.
 *
 * #1098 moves all of this into `ScoreEngine`, where the tiebreak's first server is simply remembered
 * and the arithmetic below disappears.
 */
internal fun rotatesServe(
    before: ScoreState,
    after: ScoreState,
): Boolean =
    when {
        // Only `setTo` clears the flag, so this is exactly "a tiebreak was just banked as a set".
        before.isTiebreak && !after.isTiebreak -> handovers(points = tiebreakPoints(state = before)) % 2 == 0
        // Still in the tiebreak. Requires the total to have RISEN, so a pause or a server correction
        // mid-tiebreak is not mistaken for a point.
        before.isTiebreak ->
            tiebreakPoints(state = after) > tiebreakPoints(state = before) &&
                tiebreakPoints(state = after) % 2 == 1
        else -> gamesPlayed(state = after) > gamesPlayed(state = before)
    }

/** Points played in a tiebreak so far. Both sides, because the serving sequence counts the total. */
private fun tiebreakPoints(state: ScoreState): Int = state.pointsTeam1 + state.pointsTeam2

/** How often the serve changed hands over a tiebreak of [points] points: one per odd running total. */
private fun handovers(points: Int): Int = (points + 1) / 2
