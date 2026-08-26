// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.event

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.jetbrains.exposed.sql.transactions.transaction
import org.skopeo.common.dto.event.EventParticipantResponse
import org.skopeo.common.dto.event.EventPublicResponse
import org.skopeo.common.dto.event.EventResponse
import org.skopeo.common.dto.event.MyEventResponse
import org.skopeo.common.dto.match.MatchPublicPlayer
import org.skopeo.common.dto.match.MatchPublicResponse
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.dto.event.toResponse
import org.skopeo.domain.mapper.dto.match.toPublicResponse
import org.skopeo.domain.mapper.entity.club.toDomain
import org.skopeo.domain.mapper.entity.event.toDomain
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuditEntityType
import org.skopeo.domain.model.AuditWrite
import org.skopeo.domain.model.CreateEventCommand
import org.skopeo.domain.model.Event
import org.skopeo.domain.model.EventClubRef
import org.skopeo.domain.model.EventCreatorRef
import org.skopeo.domain.model.EventParticipantRef
import org.skopeo.domain.model.EventParticipantStatus
import org.skopeo.domain.model.EventType
import org.skopeo.domain.model.EventView
import org.skopeo.domain.model.Match
import org.skopeo.domain.model.MatchStatus
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.ageInYears
import org.skopeo.domain.model.canSeeRawRatingOrFalse
import org.skopeo.domain.model.isExpired
import org.skopeo.domain.service.audit.AuditService
import org.skopeo.domain.service.club.ClubAccess
import org.skopeo.domain.service.rating.RatingAssembler
import org.skopeo.domain.service.settings.SettingsService
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.domain.service.user.displayName
import org.skopeo.domain.service.user.isDeleted
import org.skopeo.repository.CircuitRepository
import org.skopeo.repository.ClubRepository
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
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
    // The club the event is filed under (#313). REQUIRED since #794 — every organizer surface is
    // club-scoped, so a clubless event has no home.
    val clubId: UUID,
    // The circuit a TOURNAMENT event belongs to (#525); required for tournaments, ignored otherwise.
    val circuitId: UUID? = null,
    // The event's organizing format name (#720): SINGLES | DOUBLES | MIXED_DOUBLES. Required at create;
    // parsed/validated in [EventService.create]. Defaulted to SINGLES only to keep call sites compiling.
    val format: String = "SINGLES",
    // The event's class name (#403); a null/absent value defaults to OPEN_PLAY. Parsed in [EventService.create].
    val type: String? = null,
    // Whether finalizing this event awards ranking points per the global schedules (#559). Default true.
    val awardRankingPoints: Boolean = true,
)

/**
 * An event's APPROVED roster resolved for seeding (#714): the participant user ids to seed and the
 * generating owner (the event's creator) recorded on the seeding snapshot. Returned by
 * [EventService.rosterForSeeding] after the staff + owner-or-admin access check.
 */
data class EventSeedingRoster(
    val participantUserIds: List<UUID>,
    val generatedBy: UUID?,
)

/**
 * Events/meets (issue #138): staff (HOST/CLUB_OWNER/ADMINISTRATOR) create and manage events.
 *
 * Who may manage *which* event is [ClubAccess.mayOrganize] (#789): an ADMINISTRATOR any event, a named
 * owner of the event's club any of that club's events (however created), and an event's creator their
 * own event — the grandfathered clause that is also the whole rule for a clubless ("Open") event. The
 * one place that predicate tightens rather than widens is filing: creating or re-filing an event under a
 * club now requires owning it ([ClubAccess.mayFileUnder]), where before only the club's existence was
 * checked. Matches are associated with an event at fixture creation (enforced in MatchService, which
 * inherits the same rule). Expected failures are returned as an [Either] left ([ServiceError]).
 */
class EventService(
    private val events: EventRepository = EventRepository(),
    private val users: UserRepository = UserRepository(),
    private val matches: MatchRepository = MatchRepository(),
    private val ratings: RatingAssembler = RatingAssembler(),
    private val clubs: ClubRepository = ClubRepository(),
    private val clubAccess: ClubAccess = ClubAccess(),
    private val circuits: CircuitRepository = CircuitRepository(),
    private val awarder: EventFinalizeAwarder = EventFinalizeAwarder(),
    private val reverser: EventFinalizeReverser = EventFinalizeReverser(),
    private val ratingsReverser: EventRatingsReverser = EventRatingsReverser(),
    private val audit: AuditService = AuditService(),
    private val settings: SettingsService = SettingsService(),
) {
    /**
     * Whether the caller may see raw NTRP values on the event roster (#583): ADMINISTRATOR only,
     * honoring the per-admin preview toggle. Routes pass the result as `showRawRating` into the DTO.
     */
    fun callerCanSeeRawRating(token: VerifiedFirebaseToken): Boolean =
        users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain().canSeeRawRatingOrFalse()

    /**
     * Create an event (#116). Beyond the shape checks, this is where the global "Award ranking points"
     * flag (#641) is enforced server-side (#752): with the flag off, an `awardRankingPoints = true`
     * from a stale bundle, a partner API client, or plain curl is COERCED to false rather than
     * rejected — a caller whose UI legitimately offered the checkbox a minute ago shouldn't eat a 400 —
     * and the coercion is spelled out in the EVENT_CREATED audit entry so it isn't silent.
     *
     * This is also the one place #789 *tightens* rather than widens: filing the event under a club now
     * requires being a named owner of that club (or an ADMINISTRATOR). Before, only the club's existence
     * was checked, so any host could file an event under any club at all. A clubless event is unchanged.
     */
    fun create(
        token: VerifiedFirebaseToken,
        input: CreateEventInput,
    ): Either<ServiceError, EventResponse> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            val createdBy = caller.id
            ensure(condition = input.name.isNotBlank()) { ServiceError.Validation(message = "Event name is required") }
            ensure(condition = !input.endDate.isBefore(input.startDate)) {
                ServiceError.Validation(message = "End date cannot be before the start date")
            }
            ensureKnownUsers(users = users, ids = input.participantIds).bind()
            // Parse the required organizing format (#720): one of the TeamType enum names.
            val format = parseFormat(raw = input.format).bind()
            // Parse the optional event type (#403): one of the enum names, defaulting to OPEN_PLAY when absent.
            val type = input.type?.let { parseEventType(raw = it).bind() } ?: EventType.OPEN_PLAY
            // An optional club must exist (#313); a clubless event is fine. Existence is checked before
            // ownership so a typo'd id still reads as a 400 rather than a misleading 403.
            // The club must exist (#313); checked before the ownership gate so an unknown id stays a 400
            // rather than reading as a permission problem.
            ensureNotNull(value = clubs.findById(id = input.clubId)) {
                ServiceError.Validation(message = "Club ${input.clubId} not found")
            }
            // …and the caller must own it (#789). This is the tightening: filing under someone else's club
            // used to succeed.
            ensure(condition = clubAccess.mayFileUnder(caller = caller, clubId = input.clubId)) { ServiceError.Forbidden() }
            // A TOURNAMENT must belong to a circuit (#525); it must exist. Non-tournaments carry none.
            val circuitId = resolveCircuit(type = type, circuitId = input.circuitId).bind()
            // Server-side enforcement of the global award flag (#752): opting in while it is off is coerced.
            val awardingEnabled = settings.getAwardRankingPoints().enabled
            val awardRankingPoints = input.awardRankingPoints && awardingEnabled
            val awardCoerced = input.awardRankingPoints && !awardingEnabled
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
                            format = format,
                            type = type,
                            awardRankingPoints = awardRankingPoints,
                        ),
                ).toDomain()
            // Activity Log entry for the creation (#334); rename/set-club/delete are follow-ups.
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = createdBy,
                        action = AuditAction.EVENT_CREATED,
                        entityType = AuditEntityType.EVENT,
                        entityId = event.id,
                        // The summary stays constant: `awardRankingPoints` defaults to true when the client
                        // omits it, so while the global flag is off EVERY create would otherwise carry a
                        // coercion notice. The two details below carry the signal instead.
                        summary = "Created event ${event.name}",
                        details =
                            mapOf(
                                "publicCode" to event.publicCode,
                                "startDate" to event.startDate.toString(),
                                "endDate" to event.endDate.toString(),
                                "participants" to event.participantIds.size.toString(),
                                "clubId" to event.clubId?.toString(),
                                "format" to event.format.name,
                                "type" to event.type.name,
                                "awardRankingPoints" to event.awardRankingPoints.toString(),
                                // The request asked to award but the global flag (#641) was off (#752).
                                "awardRankingPointsCoercedByGlobalFlag" to awardCoerced.toString(),
                            ),
                    ),
            )
            toView(event = event).toResponse()
        }

    /**
     * The organizer's event list, scoped to what they may actually manage (#789): an ADMINISTRATOR sees
     * every active event; anyone else sees the events of every club they are a named owner of, plus the
     * events they created themselves. The club arm is the point — before #789 this was creator-only, so a
     * co-owner could be allowed to run their club's event and still be unable to find it here.
     */
    fun list(token: VerifiedFirebaseToken): Either<ServiceError, List<EventResponse>> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            val entries =
                if (caller.capabilities.contains(element = Capability.ADMINISTRATOR)) {
                    events.list(createdBy = null)
                } else {
                    events.listForOrganizer(createdBy = caller.id, clubIds = clubAccess.ownedClubIds(callerId = caller.id))
                }
            val views = entries.map { toView(event = it.toDomain()) }
            // Batched "has results" counts (#483) + the raw-rating reveal flag, assembled here so the route
            // stays thin and never touches the mapper: an ADMINISTRATOR sees raw NTRP values on the roster.
            val eventIds = views.map { it.event.id }
            val counts = completedResultCounts(eventIds = eventIds)
            // The rated twin (#772), batched the same way, so the list can badge a fully rated event.
            val rated = ratedResultCounts(eventIds = eventIds)
            val showRaw = callerCanSeeRawRating(token = token)
            views.map {
                it.toResponse(
                    completedMatchCount = counts[it.event.id] ?: 0,
                    ratedMatchCount = rated[it.event.id] ?: 0,
                    showRawRating = showRaw,
                )
            }
        }

    /**
     * A player's own events (#202) — every event they're on, in any status — for the Profile tab's
     * "Events history". Any authenticated user; an unprovisioned caller simply has none.
     */
    fun myEvents(token: VerifiedFirebaseToken): Either<ServiceError, List<MyEventResponse>> =
        either {
            val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
            val mine = caller?.let { events.findForParticipant(userId = it.id).map { entry -> entry.toDomain() } }.orEmpty()
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

    /**
     * The RATED recorded-result count per event id for a page of events (#772), batched in the same
     * single grouped query style as [completedResultCounts]. Compared against that count it yields the
     * event list's "Rated" badge; ids with no rated results are absent and default to 0.
     */
    fun ratedResultCounts(eventIds: List<UUID>): Map<UUID, Int> = matches.ratedResultCountByEvents(eventIds = eventIds)

    /**
     * The organizer payload for one event. Staff-gated *and* scoped to the events the caller may organize
     * (#789) — this is the manager view (roster facets, participant decisions, the ids every mutation
     * route is keyed by), so it follows the same predicate as the mutations rather than being readable by
     * any staff member anywhere. Anyone may still read the event's public page ([publicByCode]).
     */
    fun get(
        token: VerifiedFirebaseToken,
        id: UUID,
    ): Either<ServiceError, EventResponse> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            val event =
                ensureNotNull(value = events.findById(id = id)?.toDomain()) { ServiceError.NotFound(message = "Event $id not found") }
            ensure(condition = clubAccess.mayOrganize(caller = caller, event = event)) { ServiceError.Forbidden() }
            toView(event = event).toResponse()
        }

    /**
     * The manager view of an event resolved by its PUBLIC CODE (#741) — the same payload and staff
     * gating as [get], keyed the way the unified event page is addressed. `/events/{code}` is now the
     * single event view for every audience, so a staff viewer needs the organizer payload (and the
     * event's id, which every mutation route is keyed by) without first knowing that id.
     */
    fun manageByCode(
        token: VerifiedFirebaseToken,
        code: String,
    ): Either<ServiceError, EventResponse> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            val event =
                ensureNotNull(value = events.findByPublicCode(code = code)?.toDomain()) {
                    ServiceError.NotFound(message = "Event $code not found")
                }
            ensure(condition = clubAccess.mayOrganize(caller = caller, event = event)) { ServiceError.Forbidden() }
            toView(event = event).toResponse()
        }

    /**
     * Rename an event (#269). Staff-only, scoped by [ClubAccess.mayOrganize] (#789): an owner of the
     * event's club, its creator, or an ADMINISTRATOR. The name is validated (non-blank) and trimmed,
     * consistent with event creation.
     */
    fun rename(
        token: VerifiedFirebaseToken,
        id: UUID,
        name: String,
    ): Either<ServiceError, EventResponse> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            val event =
                ensureNotNull(value = events.findById(id = id)?.toDomain()) { ServiceError.NotFound(message = "Event $id not found") }
            ensure(condition = clubAccess.mayOrganize(caller = caller, event = event)) { ServiceError.Forbidden() }
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
     * RE-FILE an event under a different club (#319). Staff-only, the same authz as rename
     * ([ClubAccess.mayOrganize], #789) — plus, because re-filing is a claim on the *destination* club's
     * calendar exactly as creating is, the caller must also be allowed to file under the new club
     * ([ClubAccess.mayFileUnder]). A non-null club must exist.
     */
    fun setClub(
        token: VerifiedFirebaseToken,
        id: UUID,
        clubId: UUID,
    ): Either<ServiceError, EventResponse> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            val event =
                ensureNotNull(value = events.findById(id = id)?.toDomain()) { ServiceError.NotFound(message = "Event $id not found") }
            val isAdmin = caller.capabilities.contains(element = Capability.ADMINISTRATOR)
            ensure(condition = clubAccess.mayOrganize(caller = caller, event = event)) { ServiceError.Forbidden() }
            // A finalized event is otherwise terminal (#403), but which club an event is filed under is
            // pure bookkeeping where ratings are concerned: `clubId` is not an input to the rating
            // calculation, so re-filing one cannot invalidate a rating or a history row, and nothing needs
            // recalculating. An ADMINISTRATOR may therefore correct a mis-filed club after finalize
            // (#782) — the cheap alternative to un-finalizing (#477) or reversing ratings (#478).
            // Everyone else is still refused. The one thing this deliberately does NOT unwind is
            // already-issued tournament placement points, whose full-vs-halved schedule depends on the
            // club's `tournamentsSanctioned` flag; the audit detail below records that they were left
            // as issued rather than silently re-priced.
            if (!isAdmin) {
                ensureNotFinalized(event = event).bind()
            }
            ensureNotNull(value = clubs.findById(id = clubId)) { ServiceError.Validation(message = "Club $clubId not found") }
            // Re-filing under a club you don't own is refused for the same reason creating one there is (#789).
            ensure(condition = clubAccess.mayFileUnder(caller = caller, clubId = clubId)) { ServiceError.Forbidden() }
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
                        summary =
                            "Set event ${event.name} club to ${if (clubId == null) "Open" else clubId.toString()}" +
                                if (event.isFinalized) " (after finalize)" else "",
                        details =
                            mapOf(
                                "publicCode" to event.publicCode,
                                "oldClubId" to event.clubId?.toString(),
                                "newClubId" to clubId.toString(),
                                // Re-filing after finalize is an admin correction (#782); flag it so the
                                // Activity Log distinguishes it from an ordinary pre-finalize club change.
                                "wasFinalized" to event.isFinalized.toString(),
                                // The one consequence this does not unwind: a finalized tournament that opted
                                // into points already paid its placement schedule under the OLD club's
                                // sanctioning. Recorded so the ledger never looks silently re-priced.
                                "placementPointsLeftAsIssued" to
                                    (event.isFinalized && event.type == EventType.TOURNAMENT && event.awardRankingPoints).toString(),
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
            val event =
                ensureNotNull(value = events.findById(id = id)?.toDomain()) { ServiceError.NotFound(message = "Event $id not found") }
            events.setCalcPriority(id = id, priority = priority)
            toView(event = event.copy(calcPriority = priority)).toResponse()
        }

    /**
     * Finalize an event (#403), the terminal state that closes it to further changes and moves the
     * event's matches into the rating queue (finalize-time queuing, replacing result-upload-time
     * queuing for evented matches). Staff-only, scoped by [ClubAccess.mayOrganize] (#789): an owner of
     * the event's club, its creator, or an ADMINISTRATOR. Idempotency guard: an already-finalized event is a
     * [ServiceError.Validation] (there is no un-finalize). Audited as EVENT_FINALIZED.
     *
     * After finalize, when the event's "Award Ranking Points" flag is set (#559), each qualifying
     * fixture pays ledger awards per the global schedules ([EventFinalizeAwarder]) — one full-amount
     * row per winning-team member. The finalize state change and all awards share one DB transaction
     * for atomicity; a summary is audited as EVENT_POINTS_AWARDED. The idempotency guard (finalize is
     * terminal) means awarding runs exactly once per event, so there is no double-award path.
     *
     * The global "Award ranking points" flag (#641) is a kill switch (#752): with it off, awarding is
     * suppressed here even for an event whose own flag is set (it may have been created while the flag
     * was on). The suppression is surfaced — [EventResponse.awardingSuppressedByGlobalFlag] plus the
     * EVENT_POINTS_AWARDED audit entry — so a host never reads "finalized" as "points were paid".
     */
    fun finalize(
        token: VerifiedFirebaseToken,
        id: UUID,
    ): Either<ServiceError, EventResponse> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            val event =
                ensureNotNull(value = events.findById(id = id)?.toDomain()) { ServiceError.NotFound(message = "Event $id not found") }
            ensure(condition = clubAccess.mayOrganize(caller = caller, event = event)) { ServiceError.Forbidden() }
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
                            if (summary.suppressedByGlobalFlag) {
                                "Awarded no points for event ${event.name}: ranking-point awarding is " +
                                    "disabled by the global flag"
                            } else {
                                "Awarded ${summary.totalPoints.toPlainString()} points across " +
                                    "${summary.matchCount} matches for event ${event.name}"
                            },
                        details =
                            mapOf(
                                "publicCode" to event.publicCode,
                                "type" to event.type.name,
                                "matches" to summary.matchCount.toString(),
                                "awards" to summary.awardCount.toString(),
                                "totalPoints" to summary.totalPoints.toPlainString(),
                                // The event opted in but the global flag (#641) suppressed the payout (#752).
                                "suppressedByGlobalFlag" to summary.suppressedByGlobalFlag.toString(),
                            ),
                    ),
            )
            toView(event = event.copy(finalizedAt = now, finalizedBy = caller.id))
                .toResponse(awardingSuppressedByGlobalFlag = summary.suppressedByGlobalFlag)
        }

    /**
     * Un-finalize an event (#477): the reverse of [finalize], so a Host who spots an erroneous score can
     * reopen the event, correct it, and re-finalize. Symmetric authz (a STAFF caller who may organize the
     * event, #789). Guards: the event must exist and be finalized; and — crucially — NONE of
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
            val event =
                ensureNotNull(value = events.findById(id = id)?.toDomain()) { ServiceError.NotFound(message = "Event $id not found") }
            ensure(condition = clubAccess.mayOrganize(caller = caller, event = event)) { ServiceError.Forbidden() }
            ensure(condition = event.isFinalized) { ServiceError.Validation(message = "Event is not finalized") }
            ensure(condition = matches.listByEvent(eventId = id).map { it.toDomain() }.none { it.ratedAt != null }) {
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
            val event =
                ensureNotNull(value = events.findById(id = id)?.toDomain()) { ServiceError.NotFound(message = "Event $id not found") }
            ensure(condition = event.isFinalized) { ServiceError.Validation(message = "Event is not finalized") }
            ensure(condition = matches.listByEvent(eventId = id).map { it.toDomain() }.any { it.ratedAt != null }) {
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
     * alongside the event so they don't outlive it. Scoped by [ClubAccess.mayOrganize] (#789): an owner
     * of the event's club, its creator, or an ADMINISTRATOR.
     */
    fun delete(
        token: VerifiedFirebaseToken,
        id: UUID,
    ): Either<ServiceError, Unit> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            val event =
                ensureNotNull(value = events.findById(id = id)?.toDomain()) { ServiceError.NotFound(message = "Event $id not found") }
            ensure(condition = clubAccess.mayOrganize(caller = caller, event = event)) { ServiceError.Forbidden() }

            val eventMatches = matches.listByEvent(eventId = id).map { it.toDomain() }
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
                ensureNotNull(value = events.findById(id = eventId)?.toDomain()) {
                    ServiceError.NotFound(message = "Event $eventId not found")
                }
            ensure(condition = clubAccess.mayOrganize(caller = caller, event = event)) { ServiceError.Forbidden() }
            // The expiry gate (#310) is a separate axis from club ownership (#789); both apply.
            ensureHostMayEnter(event = event, caller = caller).bind()
            ensureNotFinalized(event = event).bind()
            ensureKnownUsers(users = users, ids = listOf(element = userId)).bind()
            val updated =
                ensureNotNull(value = events.addParticipant(eventId = eventId, userId = userId, approvedBy = caller.id)?.toDomain()) {
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
            val caller = staffCaller(users = users, token = token).bind()
            val event =
                ensureNotNull(value = events.findById(id = eventId)?.toDomain()) {
                    ServiceError.NotFound(message = "Event $eventId not found")
                }
            ensure(condition = clubAccess.mayOrganize(caller = caller, event = event)) { ServiceError.Forbidden() }
            val updated =
                ensureNotNull(value = events.removeParticipant(eventId = eventId, userId = userId)?.toDomain()) {
                    ServiceError.NotFound(message = "Event $eventId not found")
                }
            toView(event = updated).toResponse()
        }

    /**
     * Resolve an event's APPROVED roster for seeding (#714), enforcing the same access event management
     * uses: a STAFF caller who may organize the event ([ClubAccess.mayOrganize], #789). Returns the approved participant user ids and the
     * generating owner (the event's creator, for the seeding's audit column). The seeding computation
     * itself lives in [org.skopeo.domain.service.seeding.SeedingService].
     */
    fun rosterForSeeding(
        token: VerifiedFirebaseToken,
        id: UUID,
    ): Either<ServiceError, EventSeedingRoster> =
        either {
            val caller = staffCaller(users = users, token = token).bind()
            val event =
                ensureNotNull(value = events.findById(id = id)?.toDomain()) { ServiceError.NotFound(message = "Event $id not found") }
            ensure(condition = clubAccess.mayOrganize(caller = caller, event = event)) { ServiceError.Forbidden() }
            EventSeedingRoster(participantUserIds = event.participantIds, generatedBy = event.createdBy)
        }

    /**
     * Self-signup (#201): the authenticated player adds themselves to the event (by public code) as a
     * PENDING request a host then approves/holds. Any provisioned player may do this — not staff-gated.
     * Idempotent: a no-op if they're already on the event in any status. Returns the public summary.
     * Rejected for a finalized or soft-deleted event (#741) — neither takes joiners.
     */
    fun selfSignup(
        token: VerifiedFirebaseToken,
        code: String,
    ): Either<ServiceError, EventPublicResponse> =
        either {
            val caller =
                ensureNotNull(value = users.findByFirebaseUid(firebaseUid = token.uid)) {
                    ServiceError.Forbidden(message = "Create your profile before signing up for events")
                }.toDomain()
            // A deleted account (#518) is blocked from the sign-in path already, but guard defensively.
            ensure(condition = !caller.isDeleted()) {
                ServiceError.Validation(message = "A deleted account cannot sign up for events")
            }
            val event =
                ensureNotNull(value = events.findByPublicCode(code = code)?.toDomain()) {
                    ServiceError.NotFound(message = "Event $code not found")
                }
            // Lifecycle guards (#741): a finalized event is closed to roster changes and a soft-deleted one
            // survives only for traceability, so neither takes joiners. The page hides the button in both
            // cases; this guard is what makes it true for a direct POST.
            ensure(condition = event.isActive) {
                ServiceError.Validation(message = "A deleted event is not accepting sign-ups")
            }
            ensureNotFinalized(event = event).bind()
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
            val caller = staffCaller(users = users, token = token).bind()
            val actor = caller.id
            val status = parseParticipantStatus(raw = statusRaw).bind()
            val event =
                ensureNotNull(value = events.findById(id = eventId)?.toDomain()) {
                    ServiceError.NotFound(message = "Event $eventId not found")
                }
            ensure(condition = clubAccess.mayOrganize(caller = caller, event = event)) { ServiceError.Forbidden() }
            ensureNotFinalized(event = event).bind()
            ensure(condition = status == EventParticipantStatus.APPROVED || status == EventParticipantStatus.HOLD) {
                ServiceError.Validation(message = "A decision must be APPROVED or HOLD")
            }
            val approver = if (status == EventParticipantStatus.APPROVED) actor else null
            val updated =
                ensureNotNull(
                    value =
                        events.setParticipantStatus(eventId = eventId, userId = userId, status = status, approvedBy = approver)
                            ?.toDomain(),
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
                ensureNotNull(value = events.findByPublicCode(code = code)?.toDomain()) {
                    ServiceError.NotFound(message = "Event $code not found")
                }
            // The viewer's own standing (#201), so the page can show Request-to-join vs Pending/On hold.
            // Anonymous viewers (#193) have no standing → null.
            val caller = token?.let { users.findByFirebaseUid(firebaseUid = it.uid)?.toDomain() }
            val viewerStatus =
                caller
                    ?.let { c -> events.participantsOf(eventId = event.id).firstOrNull { it.userId == c.id } }
                    ?.let { it.status.name }
            val eventMatches = matches.listByEvent(eventId = event.id).map { it.toDomain() }
            val matchPlayerIds = eventMatches.flatMap { it.team1.userIds + it.team2.userIds }
            val byId =
                users
                    .findAllByIds(ids = (event.participantIds + matchPlayerIds).distinct())
                    .map { it.toDomain() }
                    .associateBy { it.id }

            val participants = publicParticipants(participantIds = event.participantIds, byId = byId)
            val matchResponses = publicMatches(eventMatches = eventMatches, byId = byId)
            // Surface the organizing club's name (#313), read-only; null for a clubless event.
            val clubEntity = event.clubId?.let { clubs.findById(id = it)?.toDomain() }
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
                format = event.format.name,
                type = event.type.name,
                isFinalized = event.isFinalized,
                awardRankingPoints = event.awardRankingPoints,
            )
        }

    /** Parse an event organizing format name (#720); an unknown name is a [ServiceError.Validation]. */
    private fun parseFormat(raw: String): Either<ServiceError, TeamType> =
        TeamType.entries.firstOrNull { it.name == raw }?.right()
            ?: ServiceError.Validation(message = "Invalid format '$raw'; expected SINGLES, DOUBLES, or MIXED_DOUBLES").left()

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
        val byId =
            users
                .findAllByIds(ids = (ids + listOfNotNull(element = event.createdBy)).distinct())
                .map { it.toDomain() }
                .associateBy { it.id }
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
        // Resolve the club (#313) for grouping/display, carrying its public code (#327) so the reference
        // can link to the club's public page (#780); null for a clubless event.
        val clubEntity = event.clubId?.let { clubs.findById(id = it)?.toDomain() }
        val club = clubEntity?.let { EventClubRef(id = it.id, name = it.name, publicCode = it.publicCode) }
        return EventView(event = event, participants = participants, creator = creator, club = club)
    }
}

/** Resolve the caller and require HOST/ADMINISTRATOR, else [ServiceError.Forbidden]. */
private fun staffCaller(
    users: UserRepository,
    token: VerifiedFirebaseToken,
): Either<ServiceError, User> {
    val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
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
 * The public roster (#138): name + shareable code only. The disambiguating facets an organizer sees —
 * sex, age, rating — are withheld from the public payload (#741) rather than gated at render time.
 */
private fun publicParticipants(
    participantIds: List<UUID>,
    byId: Map<UUID, User>,
): List<EventParticipantResponse> =
    participantIds.map { id ->
        val user = byId.getValue(key = id)
        EventParticipantResponse(
            userId = id.toString(),
            displayName = user.displayName(),
            publicCode = user.publicCode,
            isPlaceholder = user.placeholder,
            isDeleted = user.isDeleted(),
        )
    }

/** The event's fixtures resolved for their public match pages (#138/#361). */
private fun publicMatches(
    eventMatches: List<Match>,
    byId: Map<UUID, User>,
): List<MatchPublicResponse> =
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
    val loaded = users.findAllByIds(ids = distinct).map { it.toDomain() }
    return when {
        !loaded.map { it.id }.toSet().containsAll(elements = distinct) ->
            ServiceError.Validation(message = "One or more participants do not exist").left()
        loaded.any { it.isDeleted() } ->
            ServiceError.Validation(message = "A deleted account cannot be added to an event").left()
        else -> Unit.right()
    }
}
