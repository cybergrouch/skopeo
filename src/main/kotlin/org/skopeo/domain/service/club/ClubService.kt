// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.club

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.skopeo.common.dto.club.ClubPublicResponse
import org.skopeo.common.dto.club.ClubResponse
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.dto.club.toResponse
import org.skopeo.domain.mapper.entity.club.toDomain
import org.skopeo.domain.mapper.entity.event.toDomain
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuditEntityType
import org.skopeo.domain.model.AuditWrite
import org.skopeo.domain.model.Club
import org.skopeo.domain.model.ClubOwnerRef
import org.skopeo.domain.model.ClubPublicEvent
import org.skopeo.domain.model.ClubPublicView
import org.skopeo.domain.model.ClubView
import org.skopeo.domain.model.CreateClubCommand
import org.skopeo.domain.service.audit.AuditService
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.domain.service.user.displayName
import org.skopeo.repository.ClubRepository
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** Roles that may read the club list (e.g. to pick a club when creating an event, #313). */
private val CLUB_STAFF_ROLES = setOf(Capability.HOST, Capability.CLUB_OWNER, Capability.ADMINISTRATOR)
private val ADMIN_ONLY = setOf(element = Capability.ADMINISTRATOR)
private val OWNER_OR_ADMIN = setOf(Capability.CLUB_OWNER, Capability.ADMINISTRATOR)

/**
 * Admin-only management of clubs (#313): create clubs and assign/remove CLUB_OWNER(s). A club's
 * owners are surfaced with their display name + public code for the admin UI. Assigning an owner
 * records the user as an owner of *this* club; granting the CLUB_OWNER capability is a separate
 * admin action (the existing capability flow).
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
class ClubService(
    private val clubs: ClubRepository = ClubRepository(),
    private val users: UserRepository = UserRepository(),
    private val events: EventRepository = EventRepository(),
    private val matches: MatchRepository = MatchRepository(),
    private val audit: AuditService = AuditService(),
) {
    fun create(
        token: VerifiedFirebaseToken,
        name: String,
    ): Either<ServiceError, ClubResponse> =
        either {
            val adminId = requireCapability(token = token, allowed = ADMIN_ONLY).bind()
            ensure(condition = name.isNotBlank()) { ServiceError.Validation(message = "Club name is required") }
            val club = clubs.create(command = CreateClubCommand(name = name.trim(), createdBy = adminId)).toDomain()
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.CLUB_CREATED,
                        entityType = AuditEntityType.CLUB,
                        entityId = club.id,
                        summary = "Created club ${club.name}",
                        details = mapOf("clubId" to club.id.toString(), "name" to club.name),
                    ),
            )
            toView(club = club).toResponse()
        }

    /** Readable by staff (HOST/CLUB_OWNER/ADMINISTRATOR) so event creators can pick a club (#313). */
    fun list(token: VerifiedFirebaseToken): Either<ServiceError, List<ClubResponse>> =
        either {
            requireCapability(token = token, allowed = CLUB_STAFF_ROLES).bind()
            clubs.list().map { toView(club = it.toDomain()).toResponse() }
        }

    /** Rename a club (#325). ADMINISTRATOR-only; the name is validated (non-blank) and trimmed. */
    fun rename(
        token: VerifiedFirebaseToken,
        clubId: UUID,
        name: String,
    ): Either<ServiceError, ClubResponse> =
        either {
            val adminId = requireCapability(token = token, allowed = ADMIN_ONLY).bind()
            ensure(condition = name.isNotBlank()) { ServiceError.Validation(message = "Club name is required") }
            val updated =
                ensureNotNull(value = clubs.rename(id = clubId, name = name.trim())) {
                    ServiceError.NotFound(message = "Club $clubId not found")
                }.toDomain()
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.CLUB_RENAMED,
                        entityType = AuditEntityType.CLUB,
                        entityId = clubId,
                        summary = "Renamed club to ${updated.name}",
                        details = mapOf("clubId" to clubId.toString(), "name" to updated.name),
                    ),
            )
            toView(club = updated).toResponse()
        }

    /**
     * Delete a club (#325). ADMINISTRATOR-only. A soft-delete (is_active → false), like users and
     * events: the row is retired rather than removed, so it drops out of the club list but its
     * history stays intact. Deleting a missing or already-deleted club is a [ServiceError.NotFound].
     *
     * The delete cascades: the club's events and their matches are soft-deleted too, so they leave
     * the active organizer lists. This is non-destructive — public links (QR) and match history still
     * resolve for traceability, and ratings (built from historical matches) are never affected.
     */
    fun delete(
        token: VerifiedFirebaseToken,
        clubId: UUID,
    ): Either<ServiceError, Unit> =
        either {
            val adminId = requireCapability(token = token, allowed = ADMIN_ONLY).bind()
            val club =
                ensureNotNull(value = clubs.findById(id = clubId)) { ServiceError.NotFound(message = "Club $clubId not found") }.toDomain()
            ensure(condition = clubs.disable(id = clubId)) { ServiceError.NotFound(message = "Club $clubId not found") }
            val now = LocalDateTime.now()
            events.listByClub(clubId = clubId).map { it.toDomain() }.forEach { event ->
                matches.listByEvent(eventId = event.id).map { it.toDomain() }.forEach { match ->
                    matches.setActive(matchId = match.id, active = false, disabledAt = now).bind()
                }
                events.setActive(id = event.id, active = false, disabledAt = now)
            }
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.CLUB_DELETED,
                        entityType = AuditEntityType.CLUB,
                        entityId = clubId,
                        summary = "Deleted club ${club.name}",
                        details = mapOf("clubId" to clubId.toString(), "name" to club.name),
                    ),
            )
        }

    fun assignOwner(
        token: VerifiedFirebaseToken,
        clubId: UUID,
        userId: UUID,
    ): Either<ServiceError, ClubResponse> =
        either {
            val adminId = requireCapability(token = token, allowed = ADMIN_ONLY).bind()
            val owner = users.findById(id = userId).mapLeft { ServiceError.Validation(message = "Unknown user $userId") }.bind().toDomain()
            ensure(condition = owner.isActive) { ServiceError.Validation(message = "User $userId is not active") }
            // A club owner must hold the CLUB_OWNER capability (#317) — grant it first via capabilities.
            ensure(condition = owner.capabilities.contains(element = Capability.CLUB_OWNER)) {
                ServiceError.Validation(message = "User $userId does not have the CLUB_OWNER capability")
            }
            val updated =
                ensureNotNull(value = clubs.addOwner(clubId = clubId, userId = userId)) {
                    ServiceError.NotFound(message = "Club $clubId not found")
                }.toDomain()
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.CLUB_OWNER_ASSIGNED,
                        entityType = AuditEntityType.CLUB,
                        entityId = clubId,
                        summary = "Assigned ${owner.displayName() ?: owner.publicCode} as an owner of ${updated.name}",
                        details = mapOf("clubId" to clubId.toString(), "userId" to userId.toString()),
                    ),
            )
            toView(club = updated).toResponse()
        }

    fun removeOwner(
        token: VerifiedFirebaseToken,
        clubId: UUID,
        userId: UUID,
    ): Either<ServiceError, ClubResponse> =
        either {
            val adminId = requireCapability(token = token, allowed = ADMIN_ONLY).bind()
            val updated =
                ensureNotNull(value = clubs.removeOwner(clubId = clubId, userId = userId)) {
                    ServiceError.NotFound(message = "Club $clubId not found")
                }.toDomain()
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.CLUB_OWNER_REMOVED,
                        entityType = AuditEntityType.CLUB,
                        entityId = clubId,
                        summary = "Removed an owner from ${updated.name}",
                        details = mapOf("clubId" to clubId.toString(), "userId" to userId.toString()),
                    ),
            )
            toView(club = updated).toResponse()
        }

    /**
     * Read-only public summary of a club by its shareable code (#327). Viewable by anyone (anonymous
     * included, like the event/match/player public pages): the club's name plus its events split into
     * [ClubPublicView.upcoming] (still running or in the future) and [ClubPublicView.past] (already
     * ended), by end date vs today. Only non-sensitive fields are exposed — no owners/roster.
     */
    fun publicByCode(code: String): Either<ServiceError, ClubPublicResponse> =
        either {
            val club =
                ensureNotNull(value = clubs.findByPublicCode(code = code)) {
                    ServiceError.NotFound(message = "Club $code not found")
                }.toDomain()
            val today = LocalDate.now()
            // Active events under the club; a deleted club's events are soft-deleted too, so this is
            // empty for one (mirrors listByClub in the delete cascade).
            val (upcoming, past) =
                events
                    .listByClub(clubId = club.id)
                    .map { it.toDomain() }
                    .map { event ->
                        ClubPublicEvent(
                            publicCode = event.publicCode,
                            name = event.name,
                            startDate = event.startDate,
                            endDate = event.endDate,
                            eventType = event.type,
                        )
                    }.partition { !it.endDate.isBefore(today) }
            ClubPublicView(
                publicCode = club.publicCode,
                name = club.name,
                isActive = club.isActive,
                // Upcoming soonest-first; past most-recent-first.
                upcoming = upcoming.sortedBy { it.startDate },
                past = past.sortedByDescending { it.endDate },
            ).toResponse()
        }

    /** Resolve a club's owner ids to display refs (name + public code); findAllByIds drops any missing user. */
    private fun toView(club: Club): ClubView =
        ClubView(
            id = club.id,
            name = club.name,
            publicCode = club.publicCode,
            isActive = club.isActive,
            tournamentsSanctioned = club.tournamentsSanctioned,
            owners =
                users.findAllByIds(ids = club.ownerIds).map { it.toDomain() }.map {
                    ClubOwnerRef(userId = it.id, displayName = it.displayName(), publicCode = it.publicCode)
                },
        )

    /**
     * Set whether a club's tournaments are sanctioned (#525). CLUB_OWNER or ADMINISTRATOR only — the
     * flag governs the placement points a tournament may award, so it is a club-governance decision.
     */
    fun setSanction(
        token: VerifiedFirebaseToken,
        clubId: UUID,
        sanctioned: Boolean,
    ): Either<ServiceError, ClubResponse> =
        either {
            val actorId = requireCapability(token = token, allowed = OWNER_OR_ADMIN).bind()
            val updated =
                ensureNotNull(value = clubs.setSanction(id = clubId, sanctioned = sanctioned)) {
                    ServiceError.NotFound(message = "Club $clubId not found")
                }.toDomain()
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = actorId,
                        action = AuditAction.CLUB_SANCTION_CHANGED,
                        entityType = AuditEntityType.CLUB,
                        entityId = clubId,
                        summary = "Set ${updated.name} tournaments sanctioned=$sanctioned",
                        details = mapOf("clubId" to clubId.toString(), "sanctioned" to sanctioned.toString()),
                    ),
            )
            toView(club = updated).toResponse()
        }

    /** Access gate: the caller must hold one of [allowed]. Returns the caller's id (the audit actor). */
    private fun requireCapability(
        token: VerifiedFirebaseToken,
        allowed: Set<Capability>,
    ): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
        val permitted = caller != null && caller.capabilities.any { it in allowed }
        return if (caller == null || !permitted) ServiceError.Forbidden().left() else caller.id.right()
    }
}
