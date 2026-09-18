// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.match

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.CLUB_OWNER_OR_ADMIN
import org.skopeo.common.security.MATCH_MANAGEMENT_ROLES
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.Event
import org.skopeo.domain.model.Match
import org.skopeo.domain.model.MatchStatus
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.isExpired
import org.skopeo.domain.model.playHasBegun
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.UserRepository
import java.time.LocalDate
import java.util.UUID

/*
 * The rules a fixture has to satisfy, shared by creating one (MatchService) and editing its players
 * (FixturePlayerService, #957).
 *
 * Their own file for a reason beyond tidiness: a fixture edited into a state it could not have been
 * CREATED in would be a hole in whatever the creation rules were protecting. One copy is what makes
 * that guarantee true rather than aspirational — two services carrying near-identical guards is
 * exactly how the two drift.
 */

internal fun teamName(users: List<User>): String =
    users.joinToString(separator = "/") { user ->
        user.names.firstOrNull { it.type == NameType.DISPLAY && it.isActive }?.value ?: "Player"
    }

/**
 * The line-up rules a fixture edit must satisfy (#957).
 *
 * File-level because it needs only its arguments, and `MatchService` is at detekt's size limit.
 *
 * These are **the same rules `createFixture` applies**, deliberately: a fixture edited into a state it
 * could not have been created in would be a hole in whatever the creation rules were protecting. And
 * doubles stays doubles — the format is a property of the fixture, so silently dropping a player would
 * break every count that trusts it.
 */
internal fun ensureEditableLineUp(
    match: Match,
    event: Event,
    playerIds: List<UUID>,
): Either<ServiceError, Unit> =
    either {
        ensure(condition = playerIds.all { it in event.participantIds.toSet() }) {
            ServiceError.Validation(message = "All players must be participants of the event")
        }
        val expected = match.matchFormat.playersPerSide
        ensure(condition = playerIds.size == expected * 2) {
            ServiceError.Validation(
                message = "A ${match.matchFormat.name} fixture needs $expected player(s) per side",
            )
        }
    }

/**
 * Refuse an operation that would change a contest already under way (#970).
 *
 * Covers changing *who is playing* (#957): a line-up is settled the moment play begins, because the
 * live-score log, the spectator broadcast and the umpire's screen all keep the roster they started
 * with, and a scoreline already recorded belongs to the people who produced it.
 *
 * The message names the state, because "not allowed" without saying why sends an organizer looking for
 * a permission problem they do not have. [operation] completes the sentence, e.g. "Players cannot be
 * changed".
 *
 * **Deletion used to share this gate and no longer does — see [ensureDeletable] (#1052).** One
 * predicate cannot answer for both `IN_PROGRESS` and `COMPLETED`: a match being scored must not be
 * deleted, while a recorded result in an unfinalized, unrated event must be, because event delete
 * tells the organizer to delete exactly those matches first.
 *
 * Deliberately NOT applied to `uploadResult`: `LiveMatchService.finalize` records the umpire's result
 * on a match that is `IN_PROGRESS` by construction, so gating that path here would break the very flow
 * this issue protects. Re-recording a result is also the intended correction route (#969 covers how it
 * is *presented*).
 */
internal fun ensurePlayNotBegun(
    match: Match,
    operation: String,
): Either<ServiceError, Unit> =
    either {
        ensure(condition = !match.playHasBegun()) {
            val because =
                if (match.status == MatchStatus.IN_PROGRESS) {
                    "it is being scored right now"
                } else {
                    "it has already been played"
                }
            ServiceError.Conflict(message = "$operation on this match because $because.")
        }
    }

/**
 * May this fixture be deleted (#1052)? Deletion's own gate, split out of [ensurePlayNotBegun].
 *
 * `playHasBegun()` was the whole gate and conflates two states that need opposite answers. The product
 * already promises the permissive half: `DELETE /events/{id}` refuses an event holding a recorded but
 * unrated match *with advice to delete those matches first* — advice the match-delete gate then refused
 * to honour, so anyone following it hit a wall.
 *
 * | state | verdict |
 * |---|---|
 * | `SCHEDULED` / `CANCELLED` | allow — nothing was taken away |
 * | `IN_PROGRESS` | **refuse** (#970): someone is scoring it right now |
 * | `COMPLETED`, event unfinalized, unrated | allow — this is the case #1052 opened |
 * | rated | refuse — point at score correction (#911/#776) |
 * | event finalized | refuse — finalize is terminal (#403) |
 *
 * **Unrated AND unfinalized, not either.** `finalizedAt` is the human milestone and `ratedAt` the
 * machine one, and #952 put days between them; allowing on one alone would re-open the window #970
 * closed from the other side.
 *
 * **Nothing has to be undone.** Recording a result computes no rating (that is the separate trigger,
 * run at event finalization) and awards no ranking points (`EventFinalizeAwarder` runs from finalize
 * only), so an unrated match in an unfinalized event has no derived rows to reverse. Every read surface
 * already filters soft-deleted matches (#502), and standings recompute from active matches only.
 *
 * **The live-scoring log survives, deliberately.** `live_match_events` is kept at finalize so a
 * mis-finalized match can still be inspected (#934) — and a match deleted *because* it was
 * mis-finalized is precisely when you want it. The umpire credit was already folded into
 * `match_umpires` at finalize (#929) and is not aggregated anywhere, so it leaks into no count. The
 * retention sweep (#939) keys on status, not `is_active`, so a soft-deleted match's log is still
 * pruned on the normal 91-day schedule. Refusing deletion when a log exists was rejected: it would
 * block exactly the umpire-finalized-in-error case this issue exists to unblock.
 *
 * **The expiry axis (#310) applies to deleting a recorded result**, and only to that: erasing a result
 * after the event has ended is the same class of act as recording one, which #310 already reserves to
 * an ADMINISTRATOR or CLUB_OWNER. A leftover *scheduled* fixture stays deletable by a plain HOST, as
 * tidying up something nobody played takes nothing away. Club scoping (#789) is a separate axis again,
 * applied by the caller.
 */
internal fun ensureDeletable(
    match: Match,
    event: Event,
    caller: User,
): Either<ServiceError, Unit> =
    either {
        ensure(condition = match.status != MatchStatus.IN_PROGRESS) {
            ServiceError.Conflict(message = "This fixture cannot be deleted because it is being scored right now.")
        }
        ensure(condition = match.ratedAt == null) {
            ServiceError.Conflict(
                message =
                    "This match has been rated and cannot be deleted. Correct its score instead, " +
                        "which reverses the rating this match applied.",
            )
        }
        ensure(condition = !event.isFinalized) {
            ServiceError.Conflict(
                message = "This event is finalized; reverse the finalization before deleting any of its matches.",
            )
        }
        // A recorded result is data entry (#310): once the event has ended only an administrator or
        // club owner may take it back. An unplayed fixture is not.
        if (match.status == MatchStatus.COMPLETED) {
            ensureHostMayEnter(event = event, caller = caller).bind()
        }
    }

/**
 * Gate host data entry on an event (#310): once the event has ended, a plain HOST may no longer
 * create fixtures or record results on it — only an ADMINISTRATOR or a CLUB_OWNER may. A
 * [ServiceError.Conflict] otherwise.
 */
internal fun ensureHostMayEnter(
    event: Event,
    caller: User,
): Either<ServiceError, Unit> =
    either {
        val exempt = caller.capabilities.any { it in CLUB_OWNER_OR_ADMIN }
        ensure(condition = exempt || !event.isExpired(asOf = LocalDate.now())) {
            ServiceError.Conflict(message = "This event has ended; only an administrator or club owner can modify it.")
        }
    }

/**
 * Reject entering matches on a finalized event (#403): finalize is terminal and closes the event
 * to further changes, so a fixture cannot be created on it and a result cannot be recorded.
 */
internal fun ensureEventNotFinalized(event: Event): Either<ServiceError, Unit> =
    either {
        ensure(condition = !event.isFinalized) { ServiceError.Validation(message = "Event is finalized") }
    }

/**
 * The caller, if they may manage matches at all. File-level so [FixturePlayerService] can reuse the
 * exact same gate rather than growing a second, subtly different one.
 */
internal fun staffCallerOf(
    users: UserRepository,
    token: VerifiedFirebaseToken,
): Either<ServiceError, User> {
    val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
    return if (caller == null || caller.capabilities.none { it in MATCH_MANAGEMENT_ROLES }) {
        ServiceError.Forbidden().left()
    } else {
        caller.right()
    }
}

/** Resolve player ids, requiring each to exist and be active. Shared for the same reason as above. */
internal fun activeParticipants(
    users: UserRepository,
    ids: List<UUID>,
): Either<ServiceError, List<User>> =
    either {
        ids.map { id ->
            val user =
                users.findById(id = id).map { it.toDomain() }.mapLeft { ServiceError.Validation(message = "Unknown user $id") }.bind()
            ensure(condition = user.isActive) { ServiceError.Validation(message = "User $id is not active") }
            user
        }
    }
