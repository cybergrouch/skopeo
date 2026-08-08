// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.event

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.skopeo.common.dto.event.EventTeamResponse
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.dto.event.toResponse
import org.skopeo.domain.mapper.entity.event.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.CreateEventTeamCommand
import org.skopeo.domain.model.Event
import org.skopeo.domain.model.EventTeam
import org.skopeo.domain.model.EventTeamMemberRef
import org.skopeo.domain.model.EventTeamView
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.UpdateEventTeamCommand
import org.skopeo.domain.model.User
import org.skopeo.domain.model.isExpired
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.domain.service.user.displayName
import org.skopeo.domain.service.user.isDeleted
import org.skopeo.repository.EventRepository
import org.skopeo.repository.EventTeamRepository
import org.skopeo.repository.UserRepository
import java.time.LocalDate
import java.util.UUID

private val TEAM_STAFF_ROLES = setOf(Capability.HOST, Capability.CLUB_OWNER, Capability.ADMINISTRATOR)

// Roles exempt from the expired-event data-entry gate (#310): admins and club owners may still manage
// teams after an event has ended; a plain host may not.
private val TEAM_EXPIRY_EXEMPT_ROLES = setOf(Capability.CLUB_OWNER, Capability.ADMINISTRATOR)

/**
 * Durable, event-scoped teams (#720): purely organizational groupings of an event's APPROVED
 * participants. Staff-managed (HOST owns / ADMINISTRATOR or CLUB_OWNER any). Membership is exclusive —
 * a participant is in at most one team per event — and team size is validated against the event's
 * organizing format (1 for singles, 2 for doubles/mixed). Teams do NOT affect rating calculation or
 * seeding. Expected failures are returned as an [Either] left ([ServiceError]).
 */
class EventTeamService(
    private val events: EventRepository = EventRepository(),
    private val teams: EventTeamRepository = EventTeamRepository(),
    private val users: UserRepository = UserRepository(),
) {
    /** List an event's teams (#720). Staff-gated (owner-or-admin); read-only, so no expiry/finalize gate. */
    fun list(
        token: VerifiedFirebaseToken,
        eventId: UUID,
    ): Either<ServiceError, List<EventTeamResponse>> =
        either {
            authorizedEvent(token = token, eventId = eventId).bind()
            teams.listByEvent(eventId = eventId).map { toView(team = it.toDomain()).toResponse() }
        }

    /** Create a team (#720): validate membership vs the event format + roster + exclusivity; auto-name if unset. */
    fun create(
        token: VerifiedFirebaseToken,
        eventId: UUID,
        memberUserIds: List<UUID>,
        name: String?,
    ): Either<ServiceError, EventTeamResponse> =
        either {
            val event = authorizedForMutation(token = token, eventId = eventId).bind()
            validateMembers(event = event, memberIds = memberUserIds, excludeTeamId = null).bind()
            // A non-blank override wins; otherwise auto-name from the members' display names.
            val resolvedName = name?.trim()?.takeIf { it.isNotEmpty() } ?: autoName(memberIds = memberUserIds)
            val team =
                teams
                    .create(command = CreateEventTeamCommand(eventId = eventId, name = resolvedName, memberUserIds = memberUserIds))
                    .toDomain()
            toView(team = team).toResponse()
        }

    /** Update a team (#720): replace members (when supplied) and/or rename. A blank name re-auto-names. */
    fun update(
        token: VerifiedFirebaseToken,
        eventId: UUID,
        teamId: UUID,
        memberUserIds: List<UUID>?,
        name: String?,
    ): Either<ServiceError, EventTeamResponse> =
        either {
            val event = authorizedForMutation(token = token, eventId = eventId).bind()
            val existing = teamInEvent(eventId = eventId, teamId = teamId).bind()
            val newMemberIds = memberUserIds ?: existing.members.map { it.userId }
            validateMembers(event = event, memberIds = newMemberIds, excludeTeamId = teamId).bind()
            val newName =
                when {
                    name == null -> existing.name
                    name.isBlank() -> autoName(memberIds = newMemberIds)
                    else -> name.trim()
                }
            val updated =
                ensureNotNull(
                    value =
                        teams.update(
                            command = UpdateEventTeamCommand(teamId = teamId, name = newName, memberUserIds = newMemberIds),
                        )?.toDomain(),
                ) { ServiceError.NotFound(message = "Team $teamId not found") }
            toView(team = updated).toResponse()
        }

    /** Dissolve a team (#720): hard-delete it. Existing fixtures snapshot players, so they're untouched. */
    fun dissolve(
        token: VerifiedFirebaseToken,
        eventId: UUID,
        teamId: UUID,
    ): Either<ServiceError, Unit> =
        either {
            authorizedForMutation(token = token, eventId = eventId).bind()
            teamInEvent(eventId = eventId, teamId = teamId).bind()
            teams.delete(id = teamId)
            Unit
        }

    /** Resolve a team that must belong to [eventId] (#720); otherwise a [ServiceError.NotFound]. */
    private fun teamInEvent(
        eventId: UUID,
        teamId: UUID,
    ): Either<ServiceError, EventTeam> =
        either {
            val team =
                ensureNotNull(value = teams.findById(id = teamId)?.toDomain()) { ServiceError.NotFound(message = "Team $teamId not found") }
            ensure(condition = team.eventId == eventId) { ServiceError.NotFound(message = "Team $teamId not found") }
            team
        }

    /**
     * Validate a proposed roster for a team (#720): non-empty, no repeats, exactly the event format's
     * size (1 singles / 2 doubles-mixed), all APPROVED participants, and none already in another team.
     */
    private fun validateMembers(
        event: Event,
        memberIds: List<UUID>,
        excludeTeamId: UUID?,
    ): Either<ServiceError, Unit> =
        either {
            ensure(condition = memberIds.isNotEmpty()) { ServiceError.Validation(message = "A team needs at least one member") }
            ensure(condition = memberIds.size == memberIds.distinct().size) {
                ServiceError.Validation(message = "A player cannot appear more than once in a team")
            }
            val expected = if (event.format == TeamType.SINGLES) 1 else 2
            ensure(condition = memberIds.size == expected) {
                ServiceError.Validation(message = "A ${event.format.name} team needs exactly $expected member(s)")
            }
            val roster = event.participantIds.toSet()
            ensure(condition = memberIds.all { it in roster }) {
                ServiceError.Validation(message = "All team members must be approved participants of the event")
            }
            val taken = teams.memberUserIdsInEvent(eventId = event.id, excludeTeamId = excludeTeamId)
            ensure(condition = memberIds.none { it in taken }) {
                ServiceError.Validation(message = "A player is already in another team for this event")
            }
        }

    /** Auto-name a team from its members' display names, joined by "/" in slot order (#720). */
    private fun autoName(memberIds: List<UUID>): String {
        val byId = users.findAllByIds(ids = memberIds).map { it.toDomain() }.associateBy { it.id }
        return memberIds.joinToString(separator = "/") { id -> byId[id]?.displayName() ?: "Player" }
    }

    /** Resolve a team's members to display facets (#720): name/code + placeholder/deleted flags. */
    private fun toView(team: EventTeam): EventTeamView {
        val byId = users.findAllByIds(ids = team.members.map { it.userId }).map { it.toDomain() }.associateBy { it.id }
        val members =
            team.members.map { member ->
                val user = byId[member.userId]
                EventTeamMemberRef(
                    userId = member.userId,
                    position = member.position,
                    displayName = user?.displayName(),
                    publicCode = user?.publicCode,
                    placeholder = user?.placeholder ?: false,
                    deleted = user?.isDeleted() ?: false,
                )
            }
        return EventTeamView(team = team, members = members)
    }

    /** Staff-gated read access to an event (#720): owner-or-admin, but no expiry/finalize gate. */
    private fun authorizedEvent(
        token: VerifiedFirebaseToken,
        eventId: UUID,
    ): Either<ServiceError, Event> =
        either {
            val caller = teamStaffCaller(users = users, token = token).bind()
            val event =
                ensureNotNull(
                    value = events.findById(id = eventId)?.toDomain(),
                ) { ServiceError.NotFound(message = "Event $eventId not found") }
            ensure(condition = callerOwnsOrIsAdmin(caller = caller, event = event)) { ServiceError.Forbidden() }
            event
        }

    /**
     * Staff-gated write access to an event's teams (#720): owner-or-admin, plus the shared entry gates —
     * a plain host may not modify an ended event (#310), and a finalized event is closed to changes.
     */
    private fun authorizedForMutation(
        token: VerifiedFirebaseToken,
        eventId: UUID,
    ): Either<ServiceError, Event> =
        either {
            val caller = teamStaffCaller(users = users, token = token).bind()
            val event =
                ensureNotNull(
                    value = events.findById(id = eventId)?.toDomain(),
                ) { ServiceError.NotFound(message = "Event $eventId not found") }
            ensure(condition = callerOwnsOrIsAdmin(caller = caller, event = event)) { ServiceError.Forbidden() }
            val exempt = caller.capabilities.any { it in TEAM_EXPIRY_EXEMPT_ROLES }
            ensure(condition = exempt || !event.isExpired(asOf = LocalDate.now())) {
                ServiceError.Conflict(message = "This event has ended; only an administrator or club owner can modify it.")
            }
            ensure(condition = !event.isFinalized) { ServiceError.Validation(message = "Event is finalized") }
            event
        }
}

/** True when [caller] may manage [event]'s teams (#720): the event owner, an ADMINISTRATOR, or a CLUB_OWNER. */
private fun callerOwnsOrIsAdmin(
    caller: User,
    event: Event,
): Boolean {
    val isAdminOrOwner = caller.capabilities.any { it == Capability.ADMINISTRATOR || it == Capability.CLUB_OWNER }
    return isAdminOrOwner || event.createdBy == caller.id
}

/** Resolve the caller and require HOST/CLUB_OWNER/ADMINISTRATOR, else [ServiceError.Forbidden]. */
private fun teamStaffCaller(
    users: UserRepository,
    token: VerifiedFirebaseToken,
): Either<ServiceError, User> {
    val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
    return if (caller == null || caller.capabilities.none { it in TEAM_STAFF_ROLES }) ServiceError.Forbidden().left() else caller.right()
}
