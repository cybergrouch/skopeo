// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.SCORING_ROLES
import org.skopeo.domain.mapper.entity.livematch.LiveMatchEventKinds
import org.skopeo.domain.mapper.entity.livematch.kindOf
import org.skopeo.domain.mapper.entity.livematch.sideOf
import org.skopeo.domain.mapper.entity.livematch.toLoggedAction
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.LiveMatchView
import org.skopeo.domain.model.LoggedAction
import org.skopeo.domain.model.MatchStatus
import org.skopeo.domain.model.ScoreEvent
import org.skopeo.domain.model.User
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.LiveMatchRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import java.util.UUID

/**
 * Live scoring: the umpire's writes and the score everyone reads (#911, step 3b).
 *
 * Every write follows the same path, and it is worth stating because there is no shortcut in it:
 *
 * ```
 * read the log  ->  append one row  ->  re-read  ->  replay  ->  return the state
 * ```
 *
 * **Nothing is cached between requests.** Cloud Run runs `--min-instances=1 --max-instances=2`, so an
 * in-process cache would diverge the moment the second instance starts — the umpire's write lands on one
 * and the fold happens on the other. A match is a few hundred rows, so the fold costs microseconds and
 * there is nothing to optimize (`LIVE_MATCH.md` §7).
 *
 * Authorization is a **flat capability check** ([SCORING_ROLES]) with no per-event `ClubAccess` gate.
 * That is a deliberate exception to the #789 rule that every other event-scoped operation follows: an
 * umpire pool moves between clubs, and requiring a roving umpire to be made a club owner would be worse.
 * The consequence to keep in mind is that `SCORING_ROLES` composes `MATCH_MANAGEMENT_ROLES`, so any HOST
 * or CLUB_OWNER can score any live match, not only their own club's.
 */
class LiveMatchService(
    private val live: LiveMatchRepository = LiveMatchRepository(),
    private val matches: MatchRepository = MatchRepository(),
    private val users: UserRepository = UserRepository(),
) {
    /**
     * Take (or take over) the scoring of [matchId], moving the fixture to `IN_PROGRESS`.
     *
     * A **soft claim**: whoever asks gets it, and the previous holder is displaced rather than the caller
     * refused. The data is protected by the log's unique sequence constraint, not by this — so a claim
     * that could refuse would only strand a match behind a dead phone without making anything safer.
     */
    fun claim(
        token: VerifiedFirebaseToken,
        matchId: UUID,
    ): Either<ServiceError, LiveMatchView> =
        either {
            val caller = scorer(token = token).bind()
            val match = scorableMatch(matchId = matchId).bind()
            live.claim(matchId = matchId, scorerId = caller.id)
            if (match.status == MatchStatus.SCHEDULED) {
                matches.setStatus(matchId = matchId, status = MatchStatus.IN_PROGRESS.name)
            }
            view(matchId = matchId)
        }

    /** Give up the scoring of [matchId]. The fixture stays `IN_PROGRESS` — the match is still being played. */
    fun release(
        token: VerifiedFirebaseToken,
        matchId: UUID,
    ): Either<ServiceError, LiveMatchView> =
        either {
            scorer(token = token).bind()
            scorableMatch(matchId = matchId).bind()
            live.releaseClaim(matchId = matchId)
            view(matchId = matchId)
        }

    /**
     * Append one umpire action and return the resulting score.
     *
     * **Retries on a sequence collision.** Two instances can read the same log and compute the same next
     * sequence; the database lets exactly one insert win (`uq_live_match_events_sequence`) and tells the
     * loser. The loser must not simply take the next number — it has to re-read, because the event that
     * won may have changed what the umpire's action means. So it recomputes from the log that actually
     * landed and tries again.
     */
    fun record(
        token: VerifiedFirebaseToken,
        matchId: UUID,
        event: ScoreEvent,
    ): Either<ServiceError, LiveMatchView> =
        appendWithRetry(token = token, matchId = matchId) { sequence, callerId ->
            live.append(
                matchId = matchId,
                sequence = sequence,
                kind = kindOf(event = event),
                side = sideOf(event = event),
                playerId = (event as? ScoreEvent.ServerAssigned)?.playerId,
                recordedBy = callerId,
            )
        }

    /**
     * Take back the most recent action that is still in force.
     *
     * Appends a marker; it never deletes. Undoing when there is nothing left to undo is **not an error** —
     * it writes nothing and returns the unchanged score, which is what a courtside double-tap should do.
     */
    fun undo(
        token: VerifiedFirebaseToken,
        matchId: UUID,
    ): Either<ServiceError, LiveMatchView> =
        either {
            scorer(token = token).bind()
            scorableMatch(matchId = matchId).bind()
            val target = lastUndoableSequence(matchId = matchId)
            if (target == null) {
                view(matchId = matchId)
            } else {
                appendWithRetry(token = token, matchId = matchId) { sequence, callerId ->
                    live.append(
                        matchId = matchId,
                        sequence = sequence,
                        kind = LiveMatchEventKinds.UNDONE,
                        targetSequence = target,
                        recordedBy = callerId,
                    )
                }.bind()
            }
        }

    /** The current score and who is scoring it. Readable by anyone who can see the match. */
    fun view(matchId: UUID): LiveMatchView {
        val log = loggedActions(matchId = matchId)
        return LiveMatchView(
            matchId = matchId,
            state = ScoreEngine.replay(log = log),
            sequence = log.maxOfOrNull { it.sequence } ?: 0L,
            scorerId = live.scorer(matchId = matchId)?.scorerId,
        )
    }

    /**
     * The highest sequence that is still in force, which is what an undo should target.
     *
     * Derived by asking [ScoreEngine.effective] which actions survive rather than by scanning for the
     * last non-marker row: only the engine knows that a redo can bring an earlier action back, and
     * duplicating that reasoning here is how the two would drift.
     */
    private fun lastUndoableSequence(matchId: UUID): Long? =
        ScoreEngine.surviving(log = loggedActions(matchId = matchId)).maxOfOrNull { it.sequence }

    private fun loggedActions(matchId: UUID): List<LoggedAction> = live.log(matchId = matchId).map { it.toLoggedAction() }

    private fun appendWithRetry(
        token: VerifiedFirebaseToken,
        matchId: UUID,
        write: (Long, UUID) -> Boolean,
    ): Either<ServiceError, LiveMatchView> =
        either {
            val caller = scorer(token = token).bind()
            scorableMatch(matchId = matchId).bind()
            var attempt = 0
            var written = false
            while (attempt < MAX_APPEND_ATTEMPTS && !written) {
                written = write(live.lastSequence(matchId = matchId) + 1, caller.id)
                attempt += 1
            }
            ensure(condition = written) {
                ServiceError.Conflict(
                    message = "Could not record the action after $MAX_APPEND_ATTEMPTS attempts; another scorer is writing.",
                )
            }
            view(matchId = matchId)
        }

    /** The caller, if they may score at all. A flat capability check — see the class note. */
    private fun scorer(token: VerifiedFirebaseToken): Either<ServiceError, User> {
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
    private fun scorableMatch(matchId: UUID) =
        either {
            val match = matches.findById(matchId = matchId).bind().toDomain()
            ensure(condition = match.isActive) { ServiceError.Conflict(message = "Match is disabled") }
            ensure(condition = match.ratedAt == null) {
                ServiceError.Conflict(message = "Cannot score a match that has already been rated")
            }
            match
        }

    private companion object {
        /**
         * How many times to re-read and retry after losing a sequence race.
         *
         * Three is generous for two Cloud Run instances and one umpire each: losing three in a row means
         * something other than ordinary contention, and failing loudly beats spinning courtside.
         */
        const val MAX_APPEND_ATTEMPTS = 3
    }
}
