// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.event

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.skopeo.model.Capability
import org.skopeo.model.CreateEventCommand
import org.skopeo.model.Event
import org.skopeo.model.EventParticipantRef
import org.skopeo.model.EventView
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.displayName
import org.skopeo.repository.EventRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import java.time.LocalDate
import java.util.UUID

private val STAFF_ROLES = setOf(Capability.HOST, Capability.ADMINISTRATOR)

/** Event-creation input, parsed/validated at the route boundary (#116): name, date range, roster. */
data class CreateEventInput(
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val participantIds: List<UUID>,
)

/**
 * Events/meets (issue #138): HOST/ADMINISTRATOR create and manage events; an ADMINISTRATOR sees all
 * events while a HOST sees their own. Matches are associated with an event at fixture creation
 * (enforced in MatchService). Expected failures are returned as an [Either] left ([ServiceError]).
 */
class EventService(
    private val events: EventRepository = EventRepository(),
    private val users: UserRepository = UserRepository(),
) {
    fun create(
        token: VerifiedFirebaseToken,
        input: CreateEventInput,
    ): Either<ServiceError, EventView> =
        either {
            val createdBy = requireStaff(token = token).bind()
            ensure(condition = input.name.isNotBlank()) { ServiceError.Validation(message = "Event name is required") }
            ensure(condition = !input.endDate.isBefore(input.startDate)) {
                ServiceError.Validation(message = "End date cannot be before the start date")
            }
            ensureKnownUsers(ids = input.participantIds).bind()
            val event =
                events.create(
                    command =
                        CreateEventCommand(
                            name = input.name.trim(),
                            startDate = input.startDate,
                            endDate = input.endDate,
                            participantIds = input.participantIds.distinct(),
                            createdBy = createdBy,
                        ),
                )
            toView(event = event)
        }

    fun list(token: VerifiedFirebaseToken): Either<ServiceError, List<EventView>> =
        either {
            val caller = staffCaller(token = token).bind()
            val scopedTo = if (caller.capabilities.contains(element = Capability.ADMINISTRATOR)) null else caller.id
            events.list(createdBy = scopedTo).map { toView(event = it) }
        }

    fun get(
        token: VerifiedFirebaseToken,
        id: UUID,
    ): Either<ServiceError, EventView> =
        either {
            requireStaff(token = token).bind()
            val event = ensureNotNull(value = events.findById(id = id)) { ServiceError.NotFound(message = "Event $id not found") }
            toView(event = event)
        }

    fun addParticipant(
        token: VerifiedFirebaseToken,
        eventId: UUID,
        userId: UUID,
    ): Either<ServiceError, EventView> =
        either {
            requireStaff(token = token).bind()
            ensureKnownUsers(ids = listOf(element = userId)).bind()
            val updated =
                ensureNotNull(value = events.addParticipant(eventId = eventId, userId = userId)) {
                    ServiceError.NotFound(message = "Event $eventId not found")
                }
            toView(event = updated)
        }

    fun removeParticipant(
        token: VerifiedFirebaseToken,
        eventId: UUID,
        userId: UUID,
    ): Either<ServiceError, EventView> =
        either {
            requireStaff(token = token).bind()
            val updated =
                ensureNotNull(value = events.removeParticipant(eventId = eventId, userId = userId)) {
                    ServiceError.NotFound(message = "Event $eventId not found")
                }
            toView(event = updated)
        }

    /** Resolve an event's participant ids to names/codes (preserving roster order). */
    private fun toView(event: Event): EventView {
        // A participant row always references an existing user (FK), so getValue is safe.
        val byId = users.findAllByIds(ids = event.participantIds).associateBy { it.id }
        val participants =
            event.participantIds.map { id ->
                val user = byId.getValue(key = id)
                EventParticipantRef(userId = id, displayName = user.displayName(), publicCode = user.publicCode)
            }
        return EventView(event = event, participants = participants)
    }

    /** Every id must map to an existing user, else a [ServiceError.Validation]. */
    private fun ensureKnownUsers(ids: List<UUID>): Either<ServiceError, Unit> {
        val distinct = ids.distinct()
        val found = users.findAllByIds(ids = distinct).map { it.id }.toSet()
        return if (found.containsAll(elements = distinct)) {
            Unit.right()
        } else {
            ServiceError.Validation(message = "One or more participants do not exist").left()
        }
    }

    private fun requireStaff(token: VerifiedFirebaseToken): Either<ServiceError, UUID> = staffCaller(token = token).map { it.id }

    private fun staffCaller(token: VerifiedFirebaseToken): Either<ServiceError, User> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        return if (caller == null || caller.capabilities.none { it in STAFF_ROLES }) ServiceError.Forbidden().left() else caller.right()
    }
}
