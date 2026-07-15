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
import org.skopeo.model.EventClubRef
import org.skopeo.model.EventCreatorRef
import org.skopeo.model.EventParticipantRef
import org.skopeo.model.EventParticipantStatus
import org.skopeo.model.EventView
import org.skopeo.model.MatchStatus
import org.skopeo.model.MyEvent
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.ageInYears
import org.skopeo.model.displayName
import org.skopeo.model.isExpired
import org.skopeo.repository.ClubRepository
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

private val STAFF_ROLES = setOf(Capability.HOST, Capability.CLUB_OWNER, Capability.ADMINISTRATOR)

// Roles that may still enter data on an event after it has ended (#310): administrators and club
// owners are exempt from the expiry gate, unlike a plain host.
private val EXPIRY_EXEMPT_ROLES = setOf(Capability.CLUB_OWNER, Capability.ADMINISTRATOR)

/** Event-creation input, parsed/validated at the route boundary (#116): name, date range, roster, optional club. */
data class CreateEventInput(
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val participantIds: List<UUID>,
    val clubId: UUID? = null,
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
    private val clubs: ClubRepository = ClubRepository(),
) {
    fun create(
        token: VerifiedFirebaseToken,
        input: CreateEventInput,
    ): Either<ServiceError, EventView> =
        either {
            val createdBy = staffCaller(users = users, token = token).bind().id
            ensure(condition = input.name.isNotBlank()) { ServiceError.Validation(message = "Event name is required") }
            ensure(condition = !input.endDate.isBefore(input.startDate)) {
                ServiceError.Validation(message = "End date cannot be before the start date")
            }
            ensureKnownUsers(users = users, ids = input.participantIds).bind()
            // An optional club must exist (#313); a clubless event is fine.
            input.clubId?.let { clubId ->
                ensureNotNull(value = clubs.findById(id = clubId)) { ServiceError.Validation(message = "Club $clubId not found") }
            }
            val event =
                events.create(
                    command =
                        CreateEventCommand(
                            name = input.name.trim(),
                            startDate = input.startDate,
                            endDate = input.endDate,
                            participantIds = input.participantIds.distinct(),
                            createdBy = createdBy,
                            clubId = input.clubId,
                        ),
                )
            toView(event = event)
        }

    fun list(token: VerifiedFirebaseToken): Either<ServiceError, List<EventView>> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            val scopedTo = if (caller.capabilities.contains(element = Capability.ADMINISTRATOR)) null else caller.id
            events.list(createdBy = scopedTo).map { toView(event = it) }
        }

    /**
     * A player's own events (#202) — every event they're on, in any status — for the Profile tab's
     * "Events history". Any authenticated user; an unprovisioned caller simply has none.
     */
    fun myEvents(token: VerifiedFirebaseToken): Either<ServiceError, List<MyEvent>> =
        either {
            val caller = users.findByFirebaseUid(firebaseUid = token.uid)
            caller?.let { events.findForParticipant(userId = it.id) }.orEmpty()
        }

    fun get(
        token: VerifiedFirebaseToken,
        id: UUID,
    ): Either<ServiceError, EventView> =
        either {
            staffCaller(users = users, token = token).bind().id
            val event = ensureNotNull(value = events.findById(id = id)) { ServiceError.NotFound(message = "Event $id not found") }
            toView(event = event)
        }

    /**
     * Rename an event (#269). Staff-only; a HOST may rename only their own event, an ADMINISTRATOR any.
     * The name is validated (non-blank) and trimmed, consistent with event creation.
     */
    fun rename(
        token: VerifiedFirebaseToken,
        id: UUID,
        name: String,
    ): Either<ServiceError, EventView> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            val event = ensureNotNull(value = events.findById(id = id)) { ServiceError.NotFound(message = "Event $id not found") }
            val isAdmin = caller.capabilities.contains(element = Capability.ADMINISTRATOR)
            ensure(condition = isAdmin || event.createdBy == caller.id) { ServiceError.Forbidden() }
            ensure(condition = name.isNotBlank()) { ServiceError.Validation(message = "Event name is required") }
            // Existence is already confirmed above (needed for the authz check), so the rename can't miss.
            events.rename(id = id, name = name.trim())
            toView(event = event.copy(name = name.trim()))
        }

    /**
     * Set (or clear, when [clubId] is null) an event's club (#319). Staff-only; a HOST may edit only
     * their own event, an ADMINISTRATOR any — the same authz as rename. A non-null club must exist.
     */
    fun setClub(
        token: VerifiedFirebaseToken,
        id: UUID,
        clubId: UUID?,
    ): Either<ServiceError, EventView> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            val event = ensureNotNull(value = events.findById(id = id)) { ServiceError.NotFound(message = "Event $id not found") }
            val isAdmin = caller.capabilities.contains(element = Capability.ADMINISTRATOR)
            ensure(condition = isAdmin || event.createdBy == caller.id) { ServiceError.Forbidden() }
            clubId?.let { cid ->
                ensureNotNull(value = clubs.findById(id = cid)) { ServiceError.Validation(message = "Club $cid not found") }
            }
            // Existence is already confirmed above (needed for the authz check), so the update can't miss.
            events.updateClub(id = id, clubId = clubId)
            toView(event = event.copy(clubId = clubId))
        }

    /**
     * Set an event's rating-calculation processing priority (#335). ADMINISTRATOR-only — the
     * calculation is admin-run and its order is global. Persisted on the same scale as the event's
     * end date so a dragged event slots between date-ordered neighbours.
     */
    fun setCalcPriority(
        token: VerifiedFirebaseToken,
        id: UUID,
        priority: Double,
    ): Either<ServiceError, EventView> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            ensure(condition = caller.capabilities.contains(element = Capability.ADMINISTRATOR)) { ServiceError.Forbidden() }
            val event = ensureNotNull(value = events.findById(id = id)) { ServiceError.NotFound(message = "Event $id not found") }
            events.setCalcPriority(id = id, priority = priority)
            toView(event = event.copy(calcPriority = priority))
        }

    /**
     * Delete an event (#243), soft-delete via is_active. The event's matches gate it: any *rated* match
     * blocks deletion outright (results are permanent); any *recorded* (COMPLETED) but unrated match is
     * refused with advice to delete those matches first (they're still deletable while unrated, #138).
     * Remaining scheduled fixtures — the only matches that can survive the guard — are soft-disabled
     * alongside the event so they don't outlive it. A HOST may only delete their own event; an
     * ADMINISTRATOR may delete any.
     */
    fun delete(
        token: VerifiedFirebaseToken,
        id: UUID,
    ): Either<ServiceError, Unit> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            val event = ensureNotNull(value = events.findById(id = id)) { ServiceError.NotFound(message = "Event $id not found") }
            val isAdmin = caller.capabilities.contains(element = Capability.ADMINISTRATOR)
            ensure(condition = isAdmin || event.createdBy == caller.id) { ServiceError.Forbidden() }

            val eventMatches = matches.listByEvent(eventId = id)
            ensure(condition = eventMatches.none { it.ratedAt != null }) {
                ServiceError.Conflict(message = "This event has rated matches and can't be deleted")
            }
            ensure(condition = eventMatches.none { it.status == MatchStatus.COMPLETED }) {
                ServiceError.Conflict(message = "Delete this event's recorded matches first, then delete the event")
            }

            val now = LocalDateTime.now()
            // Only scheduled (unrecorded, unrated) fixtures remain; soft-disable them so none outlive the event.
            eventMatches.forEach { matches.setActive(matchId = it.id, active = false, disabledAt = now).bind() }
            events.setActive(id = id, active = false, disabledAt = now)
        }

    fun addParticipant(
        token: VerifiedFirebaseToken,
        eventId: UUID,
        userId: UUID,
    ): Either<ServiceError, EventView> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            val event =
                ensureNotNull(value = events.findById(id = eventId)) { ServiceError.NotFound(message = "Event $eventId not found") }
            ensureHostMayEnter(event = event, caller = caller).bind()
            ensureKnownUsers(users = users, ids = listOf(element = userId)).bind()
            val updated =
                ensureNotNull(value = events.addParticipant(eventId = eventId, userId = userId, approvedBy = caller.id)) {
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
            staffCaller(users = users, token = token).bind().id
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
            val actor = staffCaller(users = users, token = token).bind().id
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
        token: VerifiedFirebaseToken?,
        code: String,
    ): Either<ServiceError, EventPublicResponse> =
        either {
            val event =
                ensureNotNull(value = events.findByPublicCode(code = code)) {
                    ServiceError.NotFound(message = "Event $code not found")
                }
            // The viewer's own standing (#201), so the page can show Request-to-join vs Pending/On hold.
            // Anonymous viewers (#193) have no standing → null.
            val caller = token?.let { users.findByFirebaseUid(firebaseUid = it.uid) }
            val viewerStatus =
                caller
                    ?.let { c -> events.participantsOf(eventId = event.id).firstOrNull { it.userId == c.id } }
                    ?.let { it.status.name }
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
            // Surface the organizing club's name (#313), read-only; null for a clubless event.
            val clubEntity = event.clubId?.let { clubs.findById(id = it) }
            val clubName = clubEntity?.name
            EventPublicResponse(
                publicCode = event.publicCode,
                name = event.name,
                startDate = event.startDate.toString(),
                endDate = event.endDate.toString(),
                clubName = clubName,
                isActive = event.isActive,
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
        // Resolve participants and the filing host (#270) in a single lookup. A participant row always
        // references an existing user (FK), so getValue is safe; the creator is looked up nullably since
        // created_by is nullable (ON DELETE SET NULL) for legacy/orphaned events.
        val byId = users.findAllByIds(ids = (ids + listOfNotNull(element = event.createdBy)).distinct()).associateBy { it.id }
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
        val creator =
            event.createdBy?.let { creatorId ->
                // A non-null created_by references an existing user (FK), so getValue is safe.
                val host = byId.getValue(key = creatorId)
                EventCreatorRef(displayName = host.displayName(), publicCode = host.publicCode)
            }
        // Resolve the club (#313) to id + name for grouping/display; null for a clubless event.
        val clubEntity = event.clubId?.let { clubs.findById(id = it) }
        val club = clubEntity?.let { EventClubRef(id = it.id, name = it.name) }
        return EventView(event = event, participants = participants, creator = creator, club = club)
    }
}

/** Resolve the caller and require HOST/ADMINISTRATOR, else [ServiceError.Forbidden]. */
private fun staffCaller(
    users: UserRepository,
    token: VerifiedFirebaseToken,
): Either<ServiceError, User> {
    val caller = users.findByFirebaseUid(firebaseUid = token.uid)
    return if (caller == null || caller.capabilities.none { it in STAFF_ROLES }) ServiceError.Forbidden().left() else caller.right()
}

/**
 * Gate host data entry on an event (#310): once the event has ended, a plain HOST may no longer
 * modify it (add participants, create fixtures, record results) — only an ADMINISTRATOR or a
 * CLUB_OWNER may. A [ServiceError.Conflict] otherwise.
 */
private fun ensureHostMayEnter(
    event: Event,
    caller: User,
): Either<ServiceError, Unit> =
    either {
        val exempt = caller.capabilities.any { it in EXPIRY_EXEMPT_ROLES }
        ensure(condition = exempt || !event.isExpired(asOf = LocalDate.now())) {
            ServiceError.Conflict(message = "This event has ended; only an administrator or club owner can modify it.")
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
