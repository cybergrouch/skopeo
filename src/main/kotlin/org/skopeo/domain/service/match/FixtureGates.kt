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
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.isExpired
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
