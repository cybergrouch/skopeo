// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.event

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.skopeo.dto.event.EventParticipantResponse
import org.skopeo.dto.event.EventPublicResponse
import org.skopeo.dto.match.MatchPublicPlayer
import org.skopeo.dto.match.toPublicResponse
import org.skopeo.model.Capability
import org.skopeo.model.CreateEventCommand
import org.skopeo.model.Event
import org.skopeo.model.EventParticipantRef
import org.skopeo.model.EventView
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.ageInYears
import org.skopeo.model.displayName
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
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
    private val matches: MatchRepository = MatchRepository(),
    private val ratings: RatingRepository = RatingRepository(),
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

    /**
     * Read-only public summary of an event by its public code (#138). Visible to any authenticated
     * user (the same "public" semantics as a player profile / match page): the event details, the
     * participant roster, and the event's matches (each resolved for its public page).
     */
    fun publicByCode(code: String): Either<ServiceError, EventPublicResponse> =
        either {
            val event =
                ensureNotNull(value = events.findByPublicCode(code = code)) {
                    ServiceError.NotFound(message = "Event $code not found")
                }
            val eventMatches = matches.listByEvent(eventId = event.id)
            val matchPlayerIds = eventMatches.flatMap { it.team1.userIds + it.team2.userIds }
            val byId = users.findAllByIds(ids = (event.participantIds + matchPlayerIds).distinct()).associateBy { it.id }

            val participants =
                event.participantIds.map { id ->
                    val user = byId.getValue(key = id)
                    EventParticipantResponse(
                        userId = id.toString(),
                        displayName = user.displayName(),
                        publicCode = user.publicCode,
                    )
                }
            val matchResponses =
                eventMatches.map { match ->
                    val players =
                        (match.team1.userIds + match.team2.userIds).associateWith { id ->
                            val user = byId.getValue(key = id)
                            MatchPublicPlayer(displayName = user.displayName(), publicCode = user.publicCode)
                        }
                    match.toPublicResponse(players = players)
                }
            EventPublicResponse(
                publicCode = event.publicCode,
                name = event.name,
                startDate = event.startDate.toString(),
                endDate = event.endDate.toString(),
                participants = participants,
                matches = matchResponses,
            )
        }

    /** Resolve an event's participant ids to names/codes + roster facets (sex/age/rating, in order). */
    private fun toView(event: Event): EventView {
        // A participant row always references an existing user (FK), so getValue is safe.
        val byId = users.findAllByIds(ids = event.participantIds).associateBy { it.id }
        val ratingById = ratings.findCurrentRatings(userIds = event.participantIds)
        val participants =
            event.participantIds.map { id ->
                val user = byId.getValue(key = id)
                EventParticipantRef(
                    userId = id,
                    displayName = user.displayName(),
                    publicCode = user.publicCode,
                    sex = user.sex,
                    age = user.dateOfBirth?.let { ageInYears(dateOfBirth = it, asOf = LocalDate.now()) },
                    rating = ratingById[id],
                )
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
