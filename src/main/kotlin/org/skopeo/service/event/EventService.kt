// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.event
import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.jetbrains.exposed.sql.transactions.transaction
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.dto.event.EventParticipantResponse
import org.skopeo.dto.event.EventPublicResponse
import org.skopeo.dto.event.EventResponse
import org.skopeo.dto.event.MyEventResponse
import org.skopeo.dto.match.MatchPublicPlayer
import org.skopeo.mapper.dto.event.toResponse
import org.skopeo.mapper.dto.match.toPublicResponse
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.CreateEventCommand
import org.skopeo.model.Event
import org.skopeo.model.EventClubRef
import org.skopeo.model.EventCreatorRef
import org.skopeo.model.EventParticipantRef
import org.skopeo.model.EventParticipantStatus
import org.skopeo.model.EventType
import org.skopeo.model.EventView
import org.skopeo.model.MatchStatus
import org.skopeo.model.User
import org.skopeo.model.ageInYears
import org.skopeo.model.canSeeRawRatingOrFalse
import org.skopeo.model.isExpired
import org.skopeo.repository.CircuitRepository
import org.skopeo.repository.ClubRepository
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.service.user.displayName
import org.skopeo.service.user.isDeleted
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
    // The circuit a TOURNAMENT event belongs to (#525); required for tournaments, ignored otherwise.
    val circuitId: UUID? = null,
    // The event's class name (#403); a null/absent value defaults to OPEN_PLAY. Parsed in [EventService.create].
    val type: String? = null,
    // Whether finalizing this event awards ranking points per the global schedules (#559). Default true.
    val awardRankingPoints: Boolean = true,
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
    private val circuits: CircuitRepository = CircuitRepository(),
    private val awarder: EventFinalizeAwarder = EventFinalizeAwarder(),
    private val reverser: EventFinalizeReverser = EventFinalizeReverser(),
    private val ratingsReverser: EventRatingsReverser = EventRatingsReverser(),
    private val audit: AuditService = AuditService(),
) {
    /**
     * Whether the caller may see raw NTRP values on the event roster (#583): ADMINISTRATOR only,
     * honoring the per-admin preview toggle. Routes pass the result as `showRawRating` into the DTO.
     */
    fun callerCanSeeRawRating(token: VerifiedFirebaseToken): Boolean =
        users.findByFirebaseUid(firebaseUid = token.uid).canSeeRawRatingOrFalse()

    fun create(
        token: VerifiedFirebaseToken,
        input: CreateEventInput,
    ): Either<ServiceError, EventResponse> =
        either {
            val createdBy = staffCaller(users = users, token = token).bind().id
            ensure(condition = input.name.isNotBlank()) { ServiceError.Validation(message = "Event name is required") }
            ensure(condition = !input.endDate.isBefore(input.startDate)) {
                ServiceError.Validation(message = "End date cannot be before the start date")
            }
            ensureKnownUsers(users = users, ids = input.participantIds).bind()
            // Parse the optional event type (#403): one of the enum names, defaulting to OPEN_PLAY when absent.
            val type = input.type?.let { parseEventType(raw = it).bind() } ?: EventType.OPEN_PLAY
            // An optional club must exist (#313); a clubless event is fine.
            input.clubId?.let { clubId ->
                ensureNotNull(value = clubs.findById(id = clubId)) { ServiceError.Validation(message = "Club $clubId not found") }
            }
            // A TOURNAMENT must belong to a circuit (#525); it must exist. Non-tournaments carry none.
            val circuitId = resolveCircuit(type = type, circuitId = input.circuitId).bind()
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
                            circuitId = circuitId,
                            type = type,
                            awardRankingPoints = input.awardRankingPoints,
                        ),
                )
            // Activity Log entry for the creation (#334); rename/set-club/delete are follow-ups.
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = createdBy,
                        action = AuditAction.EVENT_CREATED,
                        entityType = AuditEntityType.EVENT,
                        entityId = event.id,
                        summary = "Created event ${event.name}",
                        details =
                            mapOf(
                                "publicCode" to event.publicCode,
                                "startDate" to event.startDate.toString(),
                                "endDate" to event.endDate.toString(),
                                "participants" to event.participantIds.size.toString(),
                                "clubId" to event.clubId?.toString(),
                                "type" to event.type.name,
                            ),
                    ),
            )
            toView(event = event).toResponse()
        }

    fun list(token: VerifiedFirebaseToken): Either<ServiceError, List<EventResponse>> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            val scopedTo = if (caller.capabilities.contains(element = Capability.ADMINISTRATOR)) null else caller.id
            val views = events.list(createdBy = scopedTo).map { toView(event = it) }
            // Batched "has results" counts (#483) + the raw-rating reveal flag, assembled here so the route
            // stays thin and never touches the mapper: an ADMINISTRATOR sees raw NTRP values on the roster.
            val counts = completedResultCounts(eventIds = views.map { it.event.id })
            val showRaw = callerCanSeeRawRating(token = token)
            views.map { it.toResponse(completedMatchCount = counts[it.event.id] ?: 0, showRawRating = showRaw) }
        }

    /**
     * A player's own events (#202) — every event they're on, in any status — for the Profile tab's
     * "Events history". Any authenticated user; an unprovisioned caller simply has none.
     */
    fun myEvents(token: VerifiedFirebaseToken): Either<ServiceError, List<MyEventResponse>> =
        either {
            val caller = users.findByFirebaseUid(firebaseUid = token.uid)
            val mine = caller?.let { events.findForParticipant(userId = it.id) }.orEmpty()
            // Batched "has results" counts (#483) assembled here so the route stays thin.
            val counts = completedResultCounts(eventIds = mine.map { it.event.id })
            mine.map { it.toResponse(completedMatchCount = counts[it.event.id] ?: 0) }
        }

    /**
     * The recorded-result count per event id for a page of events (#483), batched in one grouped query
     * (no N+1). Event ids with no recorded results are absent from the returned map; the DTO mapping
     * defaults them to 0. Backs the client's Finalized / Unfinalized / Upcoming bucketing of the event
     * lists — the "has results" signal that keeps a not-yet-finalized event with results out of Upcoming.
     */
    fun completedResultCounts(eventIds: List<UUID>): Map<UUID, Int> = matches.completedResultCountByEvents(eventIds = eventIds)

    fun get(
        token: VerifiedFirebaseToken,
        id: UUID,
    ): Either<ServiceError, EventResponse> =
        either {
            staffCaller(users = users, token = token).bind().id
            val event = ensureNotNull(value = events.findById(id = id)) { ServiceError.NotFound(message = "Event $id not found") }
            toView(event = event).toResponse()
        }

    /**
     * Rename an event (#269). Staff-only; a HOST may rename only their own event, an ADMINISTRATOR any.
     * The name is validated (non-blank) and trimmed, consistent with event creation.
     */
    fun rename(
        token: VerifiedFirebaseToken,
        id: UUID,
        name: String,
    ): Either<ServiceError, EventResponse> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            val event = ensureNotNull(value = events.findById(id = id)) { ServiceError.NotFound(message = "Event $id not found") }
            val isAdmin = caller.capabilities.contains(element = Capability.ADMINISTRATOR)
            ensure(condition = isAdmin || event.createdBy == caller.id) { ServiceError.Forbidden() }
            ensureNotFinalized(event = event).bind()
            ensure(condition = name.isNotBlank()) { ServiceError.Validation(message = "Event name is required") }
            // Existence is already confirmed above (needed for the authz check), so the rename can't miss.
            val trimmed = name.trim()
            events.rename(id = id, name = trimmed)
            // Activity Log entry for the rename (#354).
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = caller.id,
                        action = AuditAction.EVENT_RENAMED,
                        entityType = AuditEntityType.EVENT,
                        entityId = event.id,
                        summary = "Renamed event ${event.name} → $trimmed",
                        details =
                            mapOf(
                                "publicCode" to event.publicCode,
                                "oldName" to event.name,
                                "newName" to trimmed,
                            ),
                    ),
            )
            toView(event = event.copy(name = trimmed)).toResponse()
        }

    /**
     * Set (or clear, when [clubId] is null) an event's club (#319). Staff-only; a HOST may edit only
     * their own event, an ADMINISTRATOR any — the same authz as rename. A non-null club must exist.
     */
    fun setClub(
        token: VerifiedFirebaseToken,
        id: UUID,
        clubId: UUID?,
    ): Either<ServiceError, EventResponse> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            val event = ensureNotNull(value = events.findById(id = id)) { ServiceError.NotFound(message = "Event $id not found") }
            val isAdmin = caller.capabilities.contains(element = Capability.ADMINISTRATOR)
            ensure(condition = isAdmin || event.createdBy == caller.id) { ServiceError.Forbidden() }
            ensureNotFinalized(event = event).bind()
            clubId?.let { cid ->
                ensureNotNull(value = clubs.findById(id = cid)) { ServiceError.Validation(message = "Club $cid not found") }
            }
            // Existence is already confirmed above (needed for the authz check), so the update can't miss.
            events.updateClub(id = id, clubId = clubId)
            // Activity Log entry for the club change (#354).
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = caller.id,
                        action = AuditAction.EVENT_CLUB_CHANGED,
                        entityType = AuditEntityType.EVENT,
                        entityId = event.id,
                        summary = "Set event ${event.name} club to ${if (clubId == null) "Open" else clubId.toString()}",
                        details =
                            mapOf(
                                "publicCode" to event.publicCode,
                                "oldClubId" to event.clubId?.toString(),
                                "newClubId" to clubId?.toString(),
                            ),
                    ),
            )
            toView(event = event.copy(clubId = clubId)).toResponse()
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
    ): Either<ServiceError, EventResponse> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            ensure(condition = caller.capabilities.contains(element = Capability.ADMINISTRATOR)) { ServiceError.Forbidden() }
            val event = ensureNotNull(value = events.findById(id = id)) { ServiceError.NotFound(message = "Event $id not found") }
            events.setCalcPriority(id = id, priority = priority)
            toView(event = event.copy(calcPriority = priority)).toResponse()
        }

    /**
     * Finalize an event (#403), the terminal state that closes it to further changes and moves the
     * event's matches into the rating queue (finalize-time queuing, replacing result-upload-time
     * queuing for evented matches). Staff-only: a HOST may finalize only their own event, an
     * ADMINISTRATOR/CLUB_OWNER any. Idempotency guard: an already-finalized event is a
     * [ServiceError.Validation] (there is no un-finalize). Audited as EVENT_FINALIZED.
     *
     * After finalize, when the event's "Award Ranking Points" flag is set (#559), each qualifying
     * fixture pays ledger awards per the global schedules ([EventFinalizeAwarder]) — one full-amount
     * row per winning-team member. The finalize state change and all awards share one DB transaction
     * for atomicity; a summary is audited as EVENT_POINTS_AWARDED. The idempotency guard (finalize is
     * terminal) means awarding runs exactly once per event, so there is no double-award path.
     */
    fun finalize(
        token: VerifiedFirebaseToken,
        id: UUID,
    ): Either<ServiceError, EventResponse> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            val event = ensureNotNull(value = events.findById(id = id)) { ServiceError.NotFound(message = "Event $id not found") }
            val isAdminOrOwner = caller.capabilities.any { it == Capability.ADMINISTRATOR || it == Capability.CLUB_OWNER }
            ensure(condition = isAdminOrOwner || event.createdBy == caller.id) { ServiceError.Forbidden() }
            ensure(condition = event.isActive) { ServiceError.Validation(message = "A deleted event cannot be finalized") }
            ensure(condition = !event.isFinalized) { ServiceError.Validation(message = "Event is already finalized") }
            val now = LocalDateTime.now()
            // Finalize + all awards in one transaction: an award failure rolls back the finalize too.
            val summary =
                transaction {
                    events.finalize(id = id, finalizedAt = now, finalizedBy = caller.id)
                    awarder.awardForFinalizedEvent(event = event, grantedBy = caller.id, now = now)
                }
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = caller.id,
                        action = AuditAction.EVENT_FINALIZED,
                        entityType = AuditEntityType.EVENT,
                        entityId = event.id,
                        summary = "Finalized event ${event.name}",
                        details =
                            mapOf(
                                "publicCode" to event.publicCode,
                                "type" to event.type.name,
                            ),
                    ),
            )
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = caller.id,
                        action = AuditAction.EVENT_POINTS_AWARDED,
                        entityType = AuditEntityType.EVENT,
                        entityId = event.id,
                        summary =
                            "Awarded ${summary.totalPoints.toPlainString()} points across " +
                                "${summary.matchCount} matches for event ${event.name}",
                        details =
                            mapOf(
                                "publicCode" to event.publicCode,
                                "type" to event.type.name,
                                "matches" to summary.matchCount.toString(),
                                "awards" to summary.awardCount.toString(),
                                "totalPoints" to summary.totalPoints.toPlainString(),
                            ),
                    ),
            )
            toView(event = event.copy(finalizedAt = now, finalizedBy = caller.id)).toResponse()
        }

    /**
     * Un-finalize an event (#477): the reverse of [finalize], so a Host who spots an erroneous score can
     * reopen the event, correct it, and re-finalize. Symmetric authz (STAFF caller; event owner or an
     * ADMINISTRATOR/CLUB_OWNER). Guards: the event must exist and be finalized; and — crucially — NONE of
     * its matches may already be rated. A rated match means the bad score is already baked into rating
     * history, which un-finalize cannot reverse; those cases need the heavier rating-history correction
     * path (a companion issue). Reversal, in one transaction: revoke every ACTIVE award the finalize
     * produced (via [RankingPointRepository.revoke], leaving the append-only trail intact) then clear the
     * finalize flag. Audited as EVENT_UNFINALIZED.
     */
    fun unfinalize(
        token: VerifiedFirebaseToken,
        id: UUID,
    ): Either<ServiceError, EventResponse> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            val event = ensureNotNull(value = events.findById(id = id)) { ServiceError.NotFound(message = "Event $id not found") }
            val isAdminOrOwner = caller.capabilities.any { it == Capability.ADMINISTRATOR || it == Capability.CLUB_OWNER }
            ensure(condition = isAdminOrOwner || event.createdBy == caller.id) { ServiceError.Forbidden() }
            ensure(condition = event.isFinalized) { ServiceError.Validation(message = "Event is not finalized") }
            ensure(condition = matches.listByEvent(eventId = id).none { it.ratedAt != null }) {
                ServiceError.Validation(
                    message =
                        "This event has already-rated matches; un-finalize cannot reverse rating history. " +
                            "Use the rating-calculation correction path instead.",
                )
            }
            // The reverser revokes the event's active awards + clears the flag in one transaction, then audits.
            reverser.reverse(event = event, revokedBy = caller.id, now = LocalDateTime.now())
            toView(event = event.copy(finalizedAt = null, finalizedBy = null)).toResponse()
        }

    /**
     * Reverse an already-rated event's ratings (#478): the rated-path complement of [unfinalize], which
     * refuses once any of the event's matches are RATED. Reversing rewinds rating state, so it is a
     * distinct, destructive, ADMINISTRATOR-only action (not the broader staff set) behind a mandatory
     * client confirmation.
     *
     * Guards: the event must exist (NotFound), be finalized, have at least one RATED match (else there is
     * nothing to reverse — use un-finalize), and be at the **rated tip** — no participant may have a rated
     * match dated after their in-event match(es). A not-at-tip event is refused ([ServiceError.Validation])
     * with a clear message: later matches were rated on top, so reversing this event alone would leave them
     * stale. The full transitive rewind-and-replay for that case is deliberately out of scope (#478).
     *
     * When allowed, [EventRatingsReverser] does the whole reversal in one transaction: restore each
     * participant to their pre-event rating, supersede (soft-delete) the event's rating-history rows,
     * revoke its active awards, reset `rated_at` on its matches, and clear `finalized_at`/`finalized_by` so
     * the score can be corrected and the event re-finalized. Audited as EVENT_RATINGS_REVERSED.
     */
    fun reverseRatings(
        token: VerifiedFirebaseToken,
        id: UUID,
    ): Either<ServiceError, EventResponse> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            ensure(condition = caller.capabilities.contains(element = Capability.ADMINISTRATOR)) { ServiceError.Forbidden() }
            val event = ensureNotNull(value = events.findById(id = id)) { ServiceError.NotFound(message = "Event $id not found") }
            ensure(condition = event.isFinalized) { ServiceError.Validation(message = "Event is not finalized") }
            ensure(condition = matches.listByEvent(eventId = id).any { it.ratedAt != null }) {
                ServiceError.Validation(
                    message = "This event has no rated matches to reverse; use un-finalize instead.",
                )
            }
            ensure(condition = ratings.isEventAtRatedTip(eventId = id)) {
                ServiceError.Validation(
                    message =
                        "This event's ratings can't be reversed because later matches have already been rated " +
                            "on top of them. Reversing only this event would leave those later ratings incorrect.",
                )
            }
            // The reverser restores ratings, supersedes history, revokes awards, resets rated_at, and clears
            // the finalize flag in one transaction, then audits.
            ratingsReverser.reverse(event = event, reversedBy = caller.id, now = LocalDateTime.now())
            toView(event = event.copy(finalizedAt = null, finalizedBy = null)).toResponse()
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
            // Activity Log entry for the (soft) delete (#354).
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = caller.id,
                        action = AuditAction.EVENT_DELETED,
                        entityType = AuditEntityType.EVENT,
                        entityId = event.id,
                        summary = "Deleted event ${event.name}",
                        details =
                            mapOf(
                                "publicCode" to event.publicCode,
                                "disabledFixtures" to eventMatches.size.toString(),
                            ),
                    ),
            )
        }

    fun addParticipant(
        token: VerifiedFirebaseToken,
        eventId: UUID,
        userId: UUID,
    ): Either<ServiceError, EventResponse> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            val event =
                ensureNotNull(value = events.findById(id = eventId)) { ServiceError.NotFound(message = "Event $eventId not found") }
            ensureHostMayEnter(event = event, caller = caller).bind()
            ensureNotFinalized(event = event).bind()
            ensureKnownUsers(users = users, ids = listOf(element = userId)).bind()
            val updated =
                ensureNotNull(value = events.addParticipant(eventId = eventId, userId = userId, approvedBy = caller.id)) {
                    ServiceError.NotFound(message = "Event $eventId not found")
                }
            toView(event = updated).toResponse()
        }

    fun removeParticipant(
        token: VerifiedFirebaseToken,
        eventId: UUID,
        userId: UUID,
    ): Either<ServiceError, EventResponse> =
        either {
            staffCaller(users = users, token = token).bind().id
            val updated =
                ensureNotNull(value = events.removeParticipant(eventId = eventId, userId = userId)) {
                    ServiceError.NotFound(message = "Event $eventId not found")
                }
            toView(event = updated).toResponse()
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
            // A deleted account (#518) is blocked from the sign-in path already, but guard defensively.
            ensure(condition = !caller.isDeleted()) {
                ServiceError.Validation(message = "A deleted account cannot sign up for events")
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
        statusRaw: String,
    ): Either<ServiceError, EventResponse> =
        either {
            val actor = staffCaller(users = users, token = token).bind().id
            val status = parseParticipantStatus(raw = statusRaw).bind()
            val event =
                ensureNotNull(value = events.findById(id = eventId)) { ServiceError.NotFound(message = "Event $eventId not found") }
            ensureNotFinalized(event = event).bind()
            ensure(condition = status == EventParticipantStatus.APPROVED || status == EventParticipantStatus.HOLD) {
                ServiceError.Validation(message = "A decision must be APPROVED or HOLD")
            }
            val approver = if (status == EventParticipantStatus.APPROVED) actor else null
            val updated =
                ensureNotNull(
                    value = events.setParticipantStatus(eventId = eventId, userId = userId, status = status, approvedBy = approver),
                ) { ServiceError.NotFound(message = "Event $eventId not found") }
            toView(event = updated).toResponse()
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
                        isPlaceholder = user.placeholder,
                        isDeleted = user.isDeleted(),
                    )
                }
            val matchResponses =
                eventMatches.map { match ->
                    val players =
                        (match.team1.userIds + match.team2.userIds).associateWith { id ->
                            val user = byId.getValue(key = id)
                            MatchPublicPlayer(
                                displayName = user.displayName(),
                                publicCode = user.publicCode,
                                isPlaceholder = user.placeholder,
                                isDeleted = user.isDeleted(),
                            )
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

    /** Parse an event type name (#403); an unknown name is a [ServiceError.Validation]. */
    private fun parseEventType(raw: String): Either<ServiceError, EventType> =
        EventType.entries.firstOrNull { it.name == raw }?.right()
            ?: ServiceError.Validation(message = "Invalid event type '$raw'; expected OPEN_PLAY or TOURNAMENT").left()

    /** Parse a participant-decision status name (#201); an unknown name is a [ServiceError.Validation]. */
    private fun parseParticipantStatus(raw: String): Either<ServiceError, EventParticipantStatus> =
        EventParticipantStatus.entries.firstOrNull { it.name == raw }?.right()
            ?: ServiceError.Validation(message = "Invalid decision '$raw'; expected APPROVED or HOLD").left()

    /**
     * Resolve the circuit for a new event (#525): a TOURNAMENT must reference an existing circuit;
     * any other type carries none (a supplied id is ignored). Returns the validated id, or null.
     */
    private fun resolveCircuit(
        type: EventType,
        circuitId: UUID?,
    ): Either<ServiceError, UUID?> =
        either {
            if (type != EventType.TOURNAMENT) {
                null
            } else {
                val id = ensureNotNull(value = circuitId) { ServiceError.Validation(message = "A tournament must belong to a circuit") }
                ensureNotNull(value = circuits.findById(id = id)) { ServiceError.Validation(message = "Circuit $id not found") }
                id
            }
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
                    placeholder = user.placeholder,
                    deleted = user.isDeleted(),
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

/**
 * Reject a mutation of a finalized event (#403): finalize is terminal and closes the event to further
 * changes, so rename / set-club / participant edits are refused with a [ServiceError.Validation].
 */
private fun ensureNotFinalized(event: Event): Either<ServiceError, Unit> =
    either {
        ensure(condition = !event.isFinalized) { ServiceError.Validation(message = "Event is finalized") }
    }

/**
 * Every id must map to an existing, non-deleted user. A missing id is a [ServiceError.Validation]; a
 * soft-deleted account (#518) is rejected too — deletion blocks NEW event/match references (existing
 * rosters are untouched).
 */
private fun ensureKnownUsers(
    users: UserRepository,
    ids: List<UUID>,
): Either<ServiceError, Unit> {
    val distinct = ids.distinct()
    val loaded = users.findAllByIds(ids = distinct)
    return when {
        !loaded.map { it.id }.toSet().containsAll(elements = distinct) ->
            ServiceError.Validation(message = "One or more participants do not exist").left()
        loaded.any { it.isDeleted() } ->
            ServiceError.Validation(message = "A deleted account cannot be added to an event").left()
        else -> Unit.right()
    }
}
