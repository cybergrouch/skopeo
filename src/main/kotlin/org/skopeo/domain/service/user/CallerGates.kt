// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.user

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.repository.UserRepository
import java.util.UUID

/**
 * Resolve a verified token to its user id, provided that user holds **any** of [allowed].
 *
 * Returns the caller's id rather than the user, because every call site wants it for the same thing:
 * the audit actor. A [ServiceError.Forbidden] covers both "no such user" and "insufficient
 * capability" deliberately — telling an unauthorized caller which of the two it was leaks whether an
 * account exists.
 *
 * File-level and shared because this shape had been copied into `RankingPointService` and
 * `StandingsCalculationService` (and `ClubService` carries an admin-only variant of it). Three private
 * copies of an authorization check is three places for one to drift.
 */
internal fun requireAnyOf(
    users: UserRepository,
    token: VerifiedFirebaseToken,
    allowed: Set<Capability>,
): Either<ServiceError, UUID> {
    val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
    return if (caller == null || caller.capabilities.none { it in allowed }) {
        ServiceError.Forbidden().left()
    } else {
        caller.id.right()
    }
}
