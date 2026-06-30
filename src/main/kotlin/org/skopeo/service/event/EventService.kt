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
import org.skopeo.model.EventParticipantStatus
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
            val createdBy = staffCaller(token = token).bind().id
            ensure(condition = input.name.isNotBlank()) { ServiceError.Validation(message = "Event name is required") }
            ensure(condition = !input.endDate.isBefore(input.startDate)) {
                ServiceError.Validation(message = "End date cannot be before the start date")
            }
            ensureKnownUsers(users = users, ids = input.participantIds).bind()
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
            staffCaller(token = token).bind().id
            val event = ensureNotNull(value = events.findById(id = id)) { ServiceError.NotFound(message = "Event $id not found") }
            toView(event = event)
        }

    fun addParticipant(
        token: VerifiedFirebaseToken,
        eventId: UUID,
        userId: UUID,
    ): Either<ServiceError, EventView> =
        either {
            val actor = staffCaller(token = token).bind().id
            ensureKnownUsers(users = users, ids = listOf(element = userId)).bind()
            val updated =
                ensureNotNull(value = events.addParticipant(eventId = eventId, userId = userId, approvedBy = actor)) {
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
            staffCaller(token = token).bind().id
            val updated =
                ensureNotNull(value = events.removeParticipant(eventId = eventId, userId = userId)) {
                    ServiceError.NotFound(message = "Event $eventId not found")
                }
            toView(event = updated)
        }

    /**
     * Self-signup (#201): the authenticated player adds themselves to the event (by public code) as a
     * PENDING request a host then approves/holds. Any provisioned player may do this — not staff-gated.
     * Idempotent: a no-op if they're already on the event in any status. Returns the public summary.
     */
    fun selfSignup(
        token: VerifiedFirebaseToken,
        code: String,
    ): Either<ServiceError, EventPublicResponse> =
        either {
            val caller =
                ensureNotNull(value = users.findByFirebaseUid(firebaseUid = token.uid)) {
                    ServiceError.Forbidden(message = "Create your profile before signing up for events")
                }
            val event =
                ensureNotNull(value = events.findByPublicCode(code = code)) {
                    ServiceError.NotFound(message = "Event $code not found")
                }
            events.selfSignup(eventId = event.id, userId = caller.id)
            publicByCode(token = token, code = code).bind()
        }

    /**
     * Host/admin decision on a participant request (#201): APPROVE (→ full roster member) or HOLD (a
     * soft deny that stays on file and can be approved later). Staff-only.
     */
    fun decideParticipant(
        token: VerifiedFirebaseToken,
        eventId: UUID,
        userId: UUID,
        status: EventParticipantStatus,
    ): Either<ServiceError, EventView> =
        either {
            val actor = staffCaller(token = token).bind().id
            ensure(condition = status == EventParticipantStatus.APPROVED || status == EventParticipantStatus.HOLD) {
                ServiceError.Validation(message = "A decision must be APPROVED or HOLD")
            }
            val approver = if (status == EventParticipantStatus.APPROVED) actor else null
            val updated =
                ensureNotNull(
                    value = events.setParticipantStatus(eventId = eventId, userId = userId, status = status, approvedBy = approver),
                ) { ServiceError.NotFound(message = "Event $eventId not found") }
            toView(event = updated)
        }

    /**
     * Read-only public summary of an event by its public code (#138). Visible to any authenticated
     * user (the same "public" semantics as a player profile / match page): the event details, the
     * participant roster, and the event's matches (each resolved for its public page).
     */
    fun publicByCode(
        token: VerifiedFirebaseToken,
        code: String,
    ): Either<ServiceError, EventPublicResponse> =
        either {
            val event =
                ensureNotNull(value = events.findByPublicCode(code = code)) {
                    ServiceError.NotFound(message = "Event $code not found")
                }
            // The viewer's own standing (#201), so the page can show Request-to-join vs Pending/On hold.
            val caller = users.findByFirebaseUid(firebaseUid = token.uid)
            val viewerStatus =
                caller?.let { c -> events.participantsOf(eventId = event.id).firstOrNull { it.userId == c.id }?.status?.name }
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
                viewerStatus = viewerStatus,
            )
        }

    /**
     * Resolve ALL of an event's participants — APPROVED roster members and PENDING/HOLD requests
     * (#201) — to names/codes + facets (sex/age/rating) and their status, for the organizer view.
     */
    private fun toView(event: Event): EventView {
        val entries = events.participantsOf(eventId = event.id)
        val ids = entries.map { it.userId }
        // A participant row always references an existing user (FK), so getValue is safe.
        val byId = users.findAllByIds(ids = ids).associateBy { it.id }
        val ratingById = ratings.findCurrentRatings(userIds = ids)
        val participants =
            entries.map { entry ->
                val user = byId.getValue(key = entry.userId)
                EventParticipantRef(
                    userId = entry.userId,
                    displayName = user.displayName(),
                    publicCode = user.publicCode,
                    sex = user.sex,
                    age = user.dateOfBirth?.let { ageInYears(dateOfBirth = it, asOf = LocalDate.now()) },
                    rating = ratingById[entry.userId],
                    status = entry.status,
                )
            }
        return EventView(event = event, participants = participants)
    }

    private fun staffCaller(token: VerifiedFirebaseToken): Either<ServiceError, User> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        return if (caller == null || caller.capabilities.none { it in STAFF_ROLES }) ServiceError.Forbidden().left() else caller.right()
    }
}

/** Every id must map to an existing user, else a [ServiceError.Validation]. */
private fun ensureKnownUsers(
    users: UserRepository,
    ids: List<UUID>,
): Either<ServiceError, Unit> {
    val distinct = ids.distinct()
    val found = users.findAllByIds(ids = distinct).map { it.id }.toSet()
    return if (found.containsAll(elements = distinct)) {
        Unit.right()
    } else {
        ServiceError.Validation(message = "One or more participants do not exist").left()
    }
}
