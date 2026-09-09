// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.skopeo.common.dto.livematch.LiveMatchResponse
import org.skopeo.common.dto.livematch.LiveScoreEventRequest
import org.skopeo.common.dto.match.MatchResponse
import org.skopeo.common.dto.match.MatchResultRequest
import org.skopeo.common.dto.match.SetScoreRequest
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.SCORING_ROLES
import org.skopeo.domain.mapper.dto.livematch.toResponse
import org.skopeo.domain.mapper.entity.livematch.LiveMatchEventKinds
import org.skopeo.domain.mapper.entity.livematch.kindOf
import org.skopeo.domain.mapper.entity.livematch.sideOf
import org.skopeo.domain.mapper.entity.livematch.toLoggedAction
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.CompletedSet
import org.skopeo.domain.model.LiveMatchView
import org.skopeo.domain.model.LiveOutcome
import org.skopeo.domain.model.LiveOutcomeKind
import org.skopeo.domain.model.LoggedAction
import org.skopeo.domain.model.MatchCompletionReason
import org.skopeo.domain.model.MatchStatus
import org.skopeo.domain.model.ScoreEvent
import org.skopeo.domain.model.ScoreState
import org.skopeo.domain.model.TeamSide
import org.skopeo.domain.model.User
import org.skopeo.domain.service.match.MatchService
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.LiveMatchRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import org.skopeo.repository.persistence.MatchUmpireEntity
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
    private val results: MatchService = MatchService(),
    /**
     * Where spectators are told the score changed (#911 §3).
     *
     * Defaults to the no-op, which is the honest default: local development and CI have no Firestore
     * project. A deployed instance is handed the real one at startup.
     */
    private val broadcast: LiveScoreBroadcaster = NoOpLiveScoreBroadcaster,
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
    ): Either<ServiceError, LiveMatchResponse> =
        either {
            val caller = scorer(token = token).bind()
            val match = scorableMatch(matchId = matchId).bind()
            live.claim(matchId = matchId, scorerId = caller.id)
            if (match.status == MatchStatus.SCHEDULED) {
                matches.setStatus(matchId = matchId, status = MatchStatus.IN_PROGRESS.name)
            }
            published(matchId = matchId)
        }

    /** Give up the scoring of [matchId]. The fixture stays `IN_PROGRESS` — the match is still being played. */
    fun release(
        token: VerifiedFirebaseToken,
        matchId: UUID,
    ): Either<ServiceError, LiveMatchResponse> =
        either {
            scorer(token = token).bind()
            scorableMatch(matchId = matchId).bind()
            live.releaseClaim(matchId = matchId)
            published(matchId = matchId)
        }

    /**
     * Append one umpire action and return the resulting score.
     *
     * **Retries on a sequence collision.** Two instances can read the same log and compute the same next
     * sequence; the database lets exactly one insert win (`uq_live_match_events_sequence`) and tells the
     * loser. The loser must not simply take the next number — it has to re-read, because the event that
     * won may have changed what the umpire's action means. So it recomputes from the log that actually
     * landed and tries again.
     *
     * Parsing happens here rather than in the route because `routes` may not depend on `model` — a rule
     * `LayeredArchitectureTest` enforces with no exception — so a route cannot name a [ScoreEvent].
     */
    fun record(
        token: VerifiedFirebaseToken,
        matchId: UUID,
        request: LiveScoreEventRequest,
    ): Either<ServiceError, LiveMatchResponse> =
        either {
            val event = ScoreEventParser.parse(request = request).bind()
            appendWithRetry(token = token, matchId = matchId) { sequence, callerId ->
                live.append(
                    matchId = matchId,
                    sequence = sequence,
                    kind = kindOf(event = event),
                    side = sideOf(event = event),
                    playerId = (event as? ScoreEvent.ServerAssigned)?.playerId,
                    recordedBy = callerId,
                )
            }.bind()
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
    ): Either<ServiceError, LiveMatchResponse> =
        either {
            scorer(token = token).bind()
            scorableMatch(matchId = matchId).bind()
            val target = live.lastUndoableSequence(matchId = matchId)
            if (target == null) {
                // Nothing changed, so nothing to broadcast: a courtside double-tap must not push a
                // redundant document at every spectator.
                live.view(matchId = matchId).toResponse()
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

    /**
     * Write the live score into the match as a real result (#911 §8).
     *
     * **Goes through the existing `uploadResult`**, which is the whole point: ratings (#403), ranking
     * points, and score correction (#776) keep working untouched, and LiveMatch stays a front end to
     * result recording rather than a parallel store.
     *
     * The log is **not** deleted here. Finalize marks the match recorded and leaves the stack for a
     * later sweep — a mis-finalized match would otherwise have nothing left to inspect, and finalize is
     * the exact moment you most want to look (§8a).
     *
     * The umpire credit IS folded out now, into `match_umpires`, because that has to outlive the log.
     */
    fun finalize(
        token: VerifiedFirebaseToken,
        matchId: UUID,
    ): Either<ServiceError, MatchResponse> =
        either {
            scorer(token = token).bind()
            val match = scorableMatch(matchId = matchId).bind()
            val state = ScoreEngine.replay(log = live.loggedActions(matchId = matchId))
            val outcome =
                ensureNotNull(value = state.outcome) {
                    ServiceError.Conflict(
                        message =
                            "The match has not been declared over. Record a MATCH_AWARDED, RETIRED or " +
                                "DEFAULTED action before finalizing.",
                    )
                }
            val sides = mapOf(TeamSide.TEAM1 to match.team1.teamId, TeamSide.TEAM2 to match.team2.teamId)
            val recorded =
                results
                    .uploadResult(
                        token = token,
                        matchId = matchId,
                        request =
                            MatchResultRequest(
                                sets = recordableSets(state = state),
                                winnerTeamId = sides.getValue(key = outcome.winner).toString(),
                                completionReason = outcome.toCompletionReason().name,
                            ),
                    ).bind()
            live.recordUmpires(matchId = matchId, umpires = live.umpireCredit(matchId = matchId))
            live.releaseClaim(matchId = matchId)
            recorded
        }

    /** The current score and who is scoring it, for the wire. Readable by anyone who can see the match. */
    fun scoreboard(matchId: UUID): LiveMatchResponse = live.view(matchId = matchId).toResponse()

    /**
     * The score after a write, having told spectators about it.
     *
     * Every mutating path goes through here so there is exactly one place the broadcast can be
     * forgotten — and it is the same place the response is built, so the two cannot disagree about what
     * the score is. The broadcast is best-effort by contract and cannot fail the write: the log is
     * already committed, and trading a recorded point for a stale scoreboard would be the wrong way
     * round.
     */
    private fun published(matchId: UUID): LiveMatchResponse =
        live.view(matchId = matchId).toResponse().also { broadcast.publish(payload = it.toBroadcast()) }

    private fun appendWithRetry(
        token: VerifiedFirebaseToken,
        matchId: UUID,
        write: (Long, UUID) -> Boolean,
    ): Either<ServiceError, LiveMatchResponse> =
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
            published(matchId = matchId)
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

/**
 * The whole log for a match, as the domain sees it.
 *
 * File-level rather than a member: it needs nothing from the service but the repository, and
 * `LiveMatchService` is at detekt's function limit — a class that keeps growing helpers is the thing
 * that limit exists to notice.
 */
private fun LiveMatchRepository.loggedActions(matchId: UUID): List<LoggedAction> = log(matchId = matchId).map { it.toLoggedAction() }

/**
 * The highest sequence still in force, which is what an undo should target.
 *
 * Asks [ScoreEngine.surviving] rather than scanning for the last non-marker row: only the engine knows
 * that a redo can bring an earlier action back, and duplicating that reasoning here is how the two would
 * drift. It must also come from the engine *with sequences* — matching on the event value would be wrong
 * outright, since two identical `PointWon(TEAM1)` rows are equal and a cancelled one would be
 * indistinguishable from a surviving one.
 */
private fun LiveMatchRepository.lastUndoableSequence(matchId: UUID): Long? =
    ScoreEngine.surviving(log = loggedActions(matchId = matchId)).maxOfOrNull { it.sequence }

/**
 * How the live outcome reads on the permanent record.
 *
 * A retirement and a default both hand the match to the opponent, and the record says which — the two
 * are rated differently (§10), so collapsing them here would lose the distinction the rating pipeline
 * depends on.
 */
private fun LiveOutcome.toCompletionReason(): MatchCompletionReason =
    when (kind) {
        LiveOutcomeKind.COMPLETED -> MatchCompletionReason.COMPLETED
        LiveOutcomeKind.RETIRED -> MatchCompletionReason.RETIRED
        LiveOutcomeKind.DEFAULTED -> MatchCompletionReason.DEFAULTED
    }

/**
 * The sets worth writing to the record: every banked set, plus the unfinished one **if it decided
 * anything**.
 *
 * The partial set is included because §10 rates a retirement on the real score — a player who retires
 * down 1-3 was being outplayed, and dropping that would rate the match as if the games had not happened.
 *
 * A **level** partial set (3-3, or 0-0 because nobody had started) is omitted instead. Not an oversight:
 * since #917 the set winner is derived from the games, and a level set has no winner to derive — the
 * recording path would reject it. Omitting loses nothing that matters, because #925 established that a
 * level set contributes zero dominance anyway. It is the same conclusion reached from the other end.
 */
private fun recordableSets(state: ScoreState): List<SetScoreRequest> =
    (state.completedSets.map { it.toRequest() } + state.currentSetIfDecisive()).filterNotNull()

private fun CompletedSet.toRequest(): SetScoreRequest =
    SetScoreRequest(
        team1Games = gamesTeam1,
        team2Games = gamesTeam2,
        tiebreakTeam1Points = tiebreakTeam1Points,
        tiebreakTeam2Points = tiebreakTeam2Points,
    )

/** The unbanked set, when its games (or its tiebreak) actually separate the two sides. */
private fun ScoreState.currentSetIfDecisive(): SetScoreRequest? {
    val tiebreakDecides = isTiebreak && pointsTeam1 != pointsTeam2
    if (gamesTeam1 == gamesTeam2 && !tiebreakDecides) return null
    return SetScoreRequest(
        team1Games = gamesTeam1,
        team2Games = gamesTeam2,
        tiebreakTeam1Points = pointsTeam1.takeIf { isTiebreak },
        tiebreakTeam2Points = pointsTeam2.takeIf { isTiebreak },
    )
}

/**
 * Who umpired, folded out of the log so it outlives it (§8a).
 *
 * Counts every row a person wrote, including undo markers: correcting yourself is umpiring. The window
 * is their first to last action, which is what separates "umpired the match" from "tapped one point
 * during a handover".
 */
private fun LiveMatchRepository.umpireCredit(matchId: UUID): List<MatchUmpireEntity> =
    log(matchId = matchId)
        .groupBy { it.recordedBy }
        .map { (userId, rows) ->
            MatchUmpireEntity(
                userId = userId,
                eventsRecorded = rows.size,
                firstRecordedAt = rows.minOf { it.recordedAt },
                lastRecordedAt = rows.maxOf { it.recordedAt },
            )
        }

/** The current score and who is scoring it. Internal — the wire gets [scoreboard]. */
internal fun LiveMatchRepository.view(matchId: UUID): LiveMatchView {
    val log = loggedActions(matchId = matchId)
    return LiveMatchView(
        matchId = matchId,
        state = ScoreEngine.replay(log = log),
        sequence = log.maxOfOrNull { it.sequence } ?: 0L,
        scorerId = scorer(matchId = matchId)?.scorerId,
    )
}
