// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.club

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.Capability
import org.skopeo.model.Club
import org.skopeo.model.ClubOwnerRef
import org.skopeo.model.ClubView
import org.skopeo.model.CreateClubCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.displayName
import org.skopeo.repository.ClubRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import org.skopeo.service.user.VerifiedFirebaseToken
import java.util.UUID

/** Roles that may read the club list (e.g. to pick a club when creating an event, #313). */
private val CLUB_STAFF_ROLES = setOf(Capability.HOST, Capability.CLUB_OWNER, Capability.ADMINISTRATOR)

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
    private val audit: AuditService = AuditService(),
) {
    fun create(
        token: VerifiedFirebaseToken,
        name: String,
    ): Either<ServiceError, ClubView> =
        either {
            val adminId = requireAdmin(token = token).bind()
            ensure(condition = name.isNotBlank()) { ServiceError.Validation(message = "Club name is required") }
            val club = clubs.create(command = CreateClubCommand(name = name.trim(), createdBy = adminId))
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
            toView(club = club)
        }

    /** Readable by staff (HOST/CLUB_OWNER/ADMINISTRATOR) so event creators can pick a club (#313). */
    fun list(token: VerifiedFirebaseToken): Either<ServiceError, List<ClubView>> =
        either {
            requireStaff(token = token).bind()
            clubs.list().map { toView(club = it) }
        }

    fun assignOwner(
        token: VerifiedFirebaseToken,
        clubId: UUID,
        userId: UUID,
    ): Either<ServiceError, ClubView> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val owner = users.findById(id = userId).mapLeft { ServiceError.Validation(message = "Unknown user $userId") }.bind()
            ensure(condition = owner.isActive) { ServiceError.Validation(message = "User $userId is not active") }
            // A club owner must hold the CLUB_OWNER capability (#317) — grant it first via capabilities.
            ensure(condition = owner.capabilities.contains(element = Capability.CLUB_OWNER)) {
                ServiceError.Validation(message = "User $userId does not have the CLUB_OWNER capability")
            }
            val updated =
                ensureNotNull(value = clubs.addOwner(clubId = clubId, userId = userId)) {
                    ServiceError.NotFound(message = "Club $clubId not found")
                }
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
            toView(club = updated)
        }

    fun removeOwner(
        token: VerifiedFirebaseToken,
        clubId: UUID,
        userId: UUID,
    ): Either<ServiceError, ClubView> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val updated =
                ensureNotNull(value = clubs.removeOwner(clubId = clubId, userId = userId)) {
                    ServiceError.NotFound(message = "Club $clubId not found")
                }
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
            toView(club = updated)
        }

    /** Resolve a club's owner ids to display refs (name + public code); findAllByIds drops any missing user. */
    private fun toView(club: Club): ClubView =
        ClubView(
            id = club.id,
            name = club.name,
            isActive = club.isActive,
            owners =
                users.findAllByIds(ids = club.ownerIds).map {
                    ClubOwnerRef(userId = it.id, displayName = it.displayName(), publicCode = it.publicCode)
                },
        )

    /** ADMINISTRATOR-only access; returns the caller's id (the audit actor). */
    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        val isAdmin = caller != null && caller.capabilities.contains(element = Capability.ADMINISTRATOR)
        return if (caller == null || !isAdmin) ServiceError.Forbidden().left() else caller.id.right()
    }

    /** Staff (HOST/CLUB_OWNER/ADMINISTRATOR) access, for reads like the club list. */
    private fun requireStaff(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        val isStaff = caller != null && caller.capabilities.any { it in CLUB_STAFF_ROLES }
        return if (caller == null || !isStaff) ServiceError.Forbidden().left() else caller.id.right()
    }
}
