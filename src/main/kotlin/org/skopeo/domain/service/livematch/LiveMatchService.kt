// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import org.skopeo.common.dto.livematch.LiveMatchResponse
import org.skopeo.common.dto.livematch.LivePlayerResponse
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
import org.skopeo.domain.model.Match
import org.skopeo.domain.model.MatchCompletionReason
import org.skopeo.domain.model.MatchStatus
import org.skopeo.domain.model.ScoreEvent
import org.skopeo.domain.model.ScoreState
import org.skopeo.domain.model.TeamSide
import org.skopeo.domain.service.match.MatchService
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.domain.service.user.displayName
import org.skopeo.repository.LiveMatchRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import org.skopeo.repository.persistence.MatchUmpireEntity
import java.time.LocalDateTime
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
     * Where "now" comes from, for the match clock (#937).
     *
     * Injected rather than read inline so [matchTiming] stays a pure fold and its tests do not depend on
     * wall time. Production passes the real clock.
     */
    private val clock: () -> LocalDateTime = LocalDateTime::now,
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
            val caller = scorerOf(users = users, token = token).bind()
            val match = scorableMatchOf(matches = matches, matchId = matchId).bind()
            live.claim(matchId = matchId, scorerId = caller.id)
            if (match.status == MatchStatus.SCHEDULED) {
                matches.setStatus(matchId = matchId, status = MatchStatus.IN_PROGRESS.name)
            }
            published(match = match)
        }

    /** Give up the scoring of [matchId]. The fixture stays `IN_PROGRESS` — the match is still being played. */
    fun release(
        token: VerifiedFirebaseToken,
        matchId: UUID,
    ): Either<ServiceError, LiveMatchResponse> =
        either {
            scorerOf(users = users, token = token).bind()
            val match = scorableMatchOf(matches = matches, matchId = matchId).bind()
            live.releaseClaim(matchId = matchId)
            published(match = match)
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
            // Resolve the match FIRST: an unknown id is a NotFound, not "this match has not started".
            // An empty log looks exactly like an unstarted match, so checking state before existence
            // reports the wrong thing for an id that was never a match at all.
            scorableMatchOf(matches = matches, matchId = matchId).bind()
            val before = ScoreEngine.replay(log = live.loggedActions(matchId = matchId))
            if (event.isScoringAction()) scoringAllowed(state = before, event = event).bind()
            val response =
                appendWithRetry(token = token, matchId = matchId) { sequence, callerId ->
                    live.append(
                        matchId = matchId,
                        sequence = sequence,
                        kind = kindOf(event = event),
                        side = sideOf(event = event),
                        playerId = (event as? ScoreEvent.ServerAssigned)?.playerId,
                        recordedBy = callerId,
                        // The SAME clock the elapsed time is folded with. Two notions of "now" — one
                        // stamping rows, one measuring them — is how a match clock ends up reading zero.
                        recordedAt = clock(),
                    )
                }.bind()
            // A completed game hands the serve on (#985). Keyed on a game actually COMPLETING, not on the
            // event being GAME_AWARDED: most games end on the fourth point rather than an umpire
            // declaring them, and keying on the event would have rotated for the rare case only.
            //
            // Appended as its own event rather than derived, so the rotation lives in the log: undo
            // reverses it, and "who served game 4" stays answerable.
            val after = ScoreEngine.replay(log = live.loggedActions(matchId = matchId))
            if (gamesPlayed(state = after) > gamesPlayed(state = before)) {
                rotateServer(token = token, matchId = matchId).bind()
            } else {
                response
            }
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
            scorerOf(users = users, token = token).bind()
            scorableMatchOf(matches = matches, matchId = matchId).bind()
            val target = live.lastUndoableSequence(matchId = matchId)
            if (target == null) {
                // Nothing changed, so nothing to broadcast: a courtside double-tap must not push a
                // redundant document at every spectator.
                live.responseFor(matchId = matchId, now = clock(), players = rosterOf(matches = matches, users = users, matchId = matchId))
            } else {
                appendWithRetry(token = token, matchId = matchId) { sequence, callerId ->
                    live.append(
                        matchId = matchId,
                        sequence = sequence,
                        kind = LiveMatchEventKinds.UNDONE,
                        targetSequence = target,
                        recordedBy = callerId,
                        recordedAt = clock(),
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
            scorerOf(users = users, token = token).bind()
            val match = scorableMatchOf(matches = matches, matchId = matchId).bind()
            val replayed = ScoreEngine.replay(log = live.loggedActions(matchId = matchId))
            // Between sets, finalizing IS the declaration (#984): the umpire chose to stop here rather
            // than start another set, and the banked sets already say who won. Declaring it explicitly
            // keeps the log complete — "the match was awarded to X" is a fact worth recording — and the
            // winner is derived here rather than trusted from a client.
            val state =
                if (replayed.outcome == null && replayed.isBetweenSets) {
                    declareWinnerFromSets(token = token, matchId = matchId, state = replayed).bind()
                } else {
                    replayed
                }
            val outcome =
                ensureNotNull(value = state.outcome) {
                    ServiceError.Conflict(
                        message =
                            "This match has not finished. Award the set and finalize from there, or record a " +
                                "retirement or default.",
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
    fun scoreboard(matchId: UUID): LiveMatchResponse =
        live.responseFor(matchId = matchId, now = clock(), players = rosterOf(matches = matches, users = users, matchId = matchId))

    /**
     * The score after a write, having told spectators about it.
     *
     * Every mutating path goes through here so there is exactly one place the broadcast can be
     * forgotten — and it is the same place the response is built, so the two cannot disagree about what
     * the score is. The broadcast is best-effort by contract and cannot fail the write: the log is
     * already committed, and trading a recorded point for a stale scoreboard would be the wrong way
     * round.
     */
    private fun published(match: Match): LiveMatchResponse =
        live
            .responseFor(matchId = match.id, now = clock(), players = rosterOf(matches = matches, users = users, matchId = match.id))
            .also { broadcast.publish(payload = it.toBroadcast(publicCode = match.publicCode)) }

    private fun appendWithRetry(
        token: VerifiedFirebaseToken,
        matchId: UUID,
        write: (Long, UUID) -> Boolean,
    ): Either<ServiceError, LiveMatchResponse> =
        either {
            val caller = scorerOf(users = users, token = token).bind()
            val match = scorableMatchOf(matches = matches, matchId = matchId).bind()
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
            published(match = match)
        }

    /**
     * Declare the match won by whoever took more sets, and return the state with that outcome (#984).
     *
     * Only reachable between sets, where the umpire has explicitly chosen to stop. The winner is the
     * side holding more completed sets — derived from the log rather than supplied, so a client cannot
     * name the wrong one.
     *
     * A level set count refuses. Nobody has won two sets all, and inventing a winner to let a finalize
     * succeed would put a fabricated result into the record; the umpire should play the decider or
     * record a retirement.
     */
    private fun declareWinnerFromSets(
        token: VerifiedFirebaseToken,
        matchId: UUID,
        state: ScoreState,
    ): Either<ServiceError, ScoreState> =
        either {
            val byTeam1 = state.completedSets.count { it.winner == TeamSide.TEAM1 }
            val byTeam2 = state.completedSets.count { it.winner == TeamSide.TEAM2 }
            ensure(condition = byTeam1 != byTeam2) {
                ServiceError.Conflict(
                    message = "The sets are level at $byTeam1-$byTeam2, so there is no winner to record. Play a deciding set.",
                )
            }
            val winner = if (byTeam1 > byTeam2) TeamSide.TEAM1 else TeamSide.TEAM2
            appendWithRetry(token = token, matchId = matchId) { sequence, callerId ->
                live.append(
                    matchId = matchId,
                    sequence = sequence,
                    kind = kindOf(event = ScoreEvent.MatchAwarded(side = winner)),
                    side = winner.name,
                    playerId = null,
                    recordedBy = callerId,
                    recordedAt = clock(),
                )
            }.bind()
            ScoreEngine.replay(log = live.loggedActions(matchId = matchId))
        }

    /**
     * Hand the serve to the next player after a completed game (#985).
     *
     * Rotates through the roster in order, which is already correct for doubles' four-way turn — the
     * reason `ServerAssigned` names a player rather than a side. Appended as an ordinary event so undo
     * reverses it like any other, and an umpire correcting the order just assigns again.
     *
     * A no-op when nobody was serving: there is no "next" without a current, and the point guard above
     * means a game can only have been played without a server if the umpire declared it outright.
     */
    private fun rotateServer(
        token: VerifiedFirebaseToken,
        matchId: UUID,
    ): Either<ServiceError, LiveMatchResponse> =
        either {
            val order =
                rosterOf(matches = matches, users = users, matchId = matchId).mapNotNull {
                    runCatching { UUID.fromString(it.userId) }.getOrNull()
                }
            val current = ScoreEngine.replay(log = live.loggedActions(matchId = matchId)).serverId
            // Wraps: the player after the last is the first again, which is the rotation in both formats.
            val next = current?.let { order.getOrNull(index = order.indexOf(element = it) + 1) ?: order.firstOrNull() }
            if (next == null) {
                published(match = scorableMatchOf(matches = matches, matchId = matchId).bind())
            } else {
                appendWithRetry(token = token, matchId = matchId) { sequence, callerId ->
                    live.append(
                        matchId = matchId,
                        sequence = sequence,
                        kind = kindOf(event = ScoreEvent.ServerAssigned(playerId = next)),
                        side = null,
                        playerId = next,
                        recordedBy = callerId,
                        recordedAt = clock(),
                    )
                }.bind()
            }
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
 * A **level** partial set is now recorded too (#968). It used to be dropped, because a set winner is
 * derived from the games (#917) and a level set has none — so `1-1 (ret)` was stored as a retirement
 * with no score at all, while the spectator card still showed the games. The winner is nullable now, so
 * the set can say honestly that nobody won it and keep the games.
 *
 * Only a set where **nothing was played** is still omitted: 0-0 with no tiebreak points is not a set,
 * it is a retirement before the first game.
 *
 * The decisive partial set carries `abandoned = true` (#972), which is what lets the points rule tell it
 * apart from a set that was played out.
 */
private fun recordableSets(state: ScoreState): List<SetScoreRequest> =
    (state.completedSets.map { it.toRequest() } + state.currentSetIfPlayed()).filterNotNull()

private fun CompletedSet.toRequest(): SetScoreRequest =
    SetScoreRequest(
        team1Games = gamesTeam1,
        team2Games = gamesTeam2,
        tiebreakTeam1Points = tiebreakTeam1Points,
        tiebreakTeam2Points = tiebreakTeam2Points,
    )

/** The unbanked set, when its games (or its tiebreak) actually separate the two sides. */
private fun ScoreState.currentSetIfPlayed(): SetScoreRequest? {
    // Nothing was played, so there is no set — distinct from a LEVEL set, which was played and stands
    // at 1-1. Dropping the level one is what made a retirement there read as "Retired" with no score
    // at all (#968), losing games the spectator card was still showing.
    val anyGames = gamesTeam1 > 0 || gamesTeam2 > 0
    val anyTiebreakPoints = isTiebreak && (pointsTeam1 > 0 || pointsTeam2 > 0)
    if (!anyGames && !anyTiebreakPoints) return null
    return SetScoreRequest(
        team1Games = gamesTeam1,
        team2Games = gamesTeam2,
        tiebreakTeam1Points = pointsTeam1.takeIf { isTiebreak },
        tiebreakTeam2Points = pointsTeam2.takeIf { isTiebreak },
        // The unbanked set is by definition the one play stopped during (#972). This is the only place
        // that knows it: once written, `5-1` is indistinguishable from a set that was played out, so
        // leaving it unmarked here loses the fact permanently.
        abandoned = true,
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

/** The wire view plus its clock, in one place so the two cannot be assembled inconsistently. */
private fun LiveMatchRepository.responseFor(
    matchId: UUID,
    now: LocalDateTime,
    players: List<LivePlayerResponse> = emptyList(),
): LiveMatchResponse =
    view(matchId = matchId).toResponse(
        timing = matchTiming(rows = log(matchId = matchId), now = now),
        players = players,
    )

/**
 * Both sides' players, id and name, for the umpire's server picker (#943).
 *
 * Resolved here rather than carried in `ScoreState`: it is a property of the *match*, not of the
 * score, and the engine has no business knowing anyone's name.
 */
private fun rosterOf(
    matches: MatchRepository,
    users: UserRepository,
    matchId: UUID,
): List<LivePlayerResponse> {
    val match = matches.findById(matchId = matchId).getOrNull()?.toDomain() ?: return emptyList()
    val named = { ids: List<UUID>, side: TeamSide ->
        ids.map { id ->
            LivePlayerResponse(
                userId = id.toString(),
                name = users.findById(id = id).getOrNull()?.toDomain()?.displayName() ?: "Unknown",
                side = side.name,
            )
        }
    }
    return named(match.team1.userIds, TeamSide.TEAM1) + named(match.team2.userIds, TeamSide.TEAM2)
}
