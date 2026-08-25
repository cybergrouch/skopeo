// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.event

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import org.skopeo.common.error.ServiceError
import org.skopeo.domain.mapper.entity.event.toDomain
import org.skopeo.domain.model.Event
import org.skopeo.domain.model.User
import org.skopeo.domain.service.club.ClubAccess
import org.skopeo.repository.EventRepository
import java.util.UUID

/**
 * [ClubAccess.mayOrganize] as an [Either] gate, for callers that hold an event *reference* rather than the
 * event itself (#789) — chiefly [org.skopeo.domain.service.match.MatchService], where the club rule has to
 * be resolved from a match's `event_id`.
 *
 * A match with **no** event keeps the plain staff gate: there is no club to anchor ownership on, so
 * [ensureForEventId] treats a null id as allowed rather than inventing a rule for non-evented matches.
 *
 * Distinct from the event-EXPIRY gate (#310, `ensureHostMayEnter`), which is a different axis; both apply.
 */
class EventOrganizerGate(
    private val events: EventRepository = EventRepository(),
    private val access: ClubAccess = ClubAccess(),
) {
    /** Refuse unless [caller] may organize [event] — an owner of its club, its creator, or an ADMINISTRATOR. */
    fun ensure(
        event: Event,
        caller: User,
    ): Either<ServiceError, Unit> =
        either {
            ensure(condition = access.mayOrganize(caller = caller, event = event)) { ServiceError.Forbidden() }
        }

    /** [ensure] for an event referenced by id. A null [eventId] (no event) is allowed; a missing one is a NotFound. */
    fun ensureForEventId(
        eventId: UUID?,
        caller: User,
    ): Either<ServiceError, Unit> =
        either {
            eventId?.let { id ->
                val event =
                    ensureNotNull(value = events.findById(id = id)?.toDomain()) {
                        ServiceError.NotFound(message = "Event $id not found")
                    }
                ensure(event = event, caller = caller).bind()
            }
        }
}
