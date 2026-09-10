// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.match

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.skopeo.common.dto.match.MatchResponse
import org.skopeo.common.dto.match.UpdateFixturePlayersRequest
import org.skopeo.common.error.ServiceError
import org.skopeo.domain.mapper.dto.match.toResponse
import org.skopeo.domain.mapper.entity.event.toDomain
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuditEntityType
import org.skopeo.domain.model.AuditWrite
import org.skopeo.domain.model.Match
import org.skopeo.domain.service.audit.AuditService
import org.skopeo.domain.service.event.EventOrganizerGate
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import java.util.UUID

/**
 * Changing who plays a fixture (#957).
 *
 * **Its own service rather than another method on `MatchService`.** That class is at detekt's size
 * limit, and adding to it is what the limit exists to notice — a class that keeps absorbing operations
 * eventually has no describable responsibility. The gates it shares with fixture creation
 * (`staffCallerOf`, `activeParticipants`, `ensureHostMayEnter`, `ensureEventNotFinalized`) are
 * file-level and reused, so this is a genuine split rather than a second, subtly different copy of the
 * same rules.
 */
class FixturePlayerService(
    private val matches: MatchRepository = MatchRepository(),
    private val users: UserRepository = UserRepository(),
    private val events: EventRepository = EventRepository(),
    private val audit: AuditService = AuditService(),
    private val organizer: EventOrganizerGate = EventOrganizerGate(),
) {
    /**
     * Replace both sides' players, keeping the fixture itself.
     *
     * Before this the only route was delete-and-recreate, which is worse than tedious: the match number
     * is an identifier that is never recycled (#898), so recreating leaves a permanent gap and hands the
     * fixture a new number — "Match 3" becomes "Match 12" on the draw sheet people are reading from —
     * and since the number *is* the calculation order, the position has to be rebuilt by hand.
     *
     * **Refused once play has begun.** Changing who played would rewrite the history ratings and points
     * were computed from — and mid-match it also desynchronises the umpire's screen, the live-score log
     * and the spectator broadcast, which all keep the roster they started with.
     *
     * `ratedAt` is not the line, for the reason in #952: rating happens at event finalization, days
     * later. This originally named `COMPLETED` explicitly and so missed `IN_PROGRESS` entirely (#970) —
     * a status that did not exist as a writable state until #930. The shared `playHasBegun` predicate
     * replaces the enumeration.
     */
    fun updateFixturePlayers(
        token: VerifiedFirebaseToken,
        matchId: UUID,
        request: UpdateFixturePlayersRequest,
    ): Either<ServiceError, MatchResponse> =
        either {
            val caller = staffCallerOf(users = users, token = token).bind()
            val match = matches.findById(matchId = matchId).bind().toDomain()
            ensure(condition = match.isActive) { ServiceError.Conflict(message = "Match is disabled") }
            ensurePlayNotBegun(match = match, operation = "Players cannot be changed").bind()

            val event = events.getById(id = match.eventId).toDomain()
            organizer.ensure(event = event, caller = caller).bind()
            ensureHostMayEnter(event = event, caller = caller).bind()
            ensureEventNotFinalized(event = event).bind()

            val team1Ids = request.team1.map { parseUserId(raw = it).bind() }
            val team2Ids = request.team2.map { parseUserId(raw = it).bind() }
            ensureEditableLineUp(match = match, event = event, playerIds = team1Ids + team2Ids).bind()
            val team1Users = activeParticipants(users = users, ids = team1Ids).bind()
            val team2Users = activeParticipants(users = users, ids = team2Ids).bind()

            val updated =
                matches
                    .setFixturePlayers(
                        matchId = matchId,
                        team1UserIds = team1Ids,
                        team2UserIds = team2Ids,
                        team1Name = teamName(users = team1Users),
                        team2Name = teamName(users = team2Users),
                    ).bind()
                    .toDomain()
            audit.record(
                write = lineUpChanged(actorId = caller.id, match = match, team1Ids = team1Ids, team2Ids = team2Ids),
            )
            updated.toResponse()
        }
}

/** A user id from the wire, as a [ServiceError] rather than a thrown exception. */
private fun parseUserId(raw: String): Either<ServiceError, UUID> =
    either {
        val id = runCatching { UUID.fromString(raw) }.getOrNull()
        ensure(condition = id != null) { ServiceError.Validation(message = "Invalid user id '$raw'") }
        id
    }

/**
 * The audit entry for a line-up change (#957).
 *
 * Who played is exactly the kind of edit that has to be answerable later, so both sides are recorded
 * rather than just the fact that something changed.
 */
private fun lineUpChanged(
    actorId: UUID,
    match: Match,
    team1Ids: List<UUID>,
    team2Ids: List<UUID>,
): AuditWrite =
    AuditWrite(
        actorUserId = actorId,
        action = AuditAction.MATCH_FIXTURE_PLAYERS_CHANGED,
        entityType = AuditEntityType.MATCH,
        entityId = match.id,
        summary = "Changed the players on match #${match.matchNumber}",
        details =
            mapOf(
                "team1" to team1Ids.joinToString { it.toString() },
                "team2" to team2Ids.joinToString { it.toString() },
            ),
    )
