// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.invite

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.Capability
import org.skopeo.model.Invite
import org.skopeo.model.InvitePage
import org.skopeo.model.InviteStatus
import org.skopeo.model.ServiceError
import org.skopeo.repository.InviteRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import org.skopeo.service.user.VerifiedFirebaseToken
import java.time.LocalDateTime
import java.util.UUID

private const val MAX_PAGE_SIZE = 100
private const val INVITE_TTL_DAYS = 7L

/**
 * Admin-only management of onboarding invites (issue #74). Creating an invite is idempotent per
 * email: re-inviting a still-pending email rotates its 7-day expiry rather than stacking duplicates
 * (the "resend" affordance). The actual email-link send happens client-side (Firebase); this records
 * the invite that the provisioning gate later checks.
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
class InviteService(
    private val invites: InviteRepository = InviteRepository(),
    private val users: UserRepository = UserRepository(),
    private val audit: AuditService = AuditService(),
) {
    /** [email] is validated and normalized (trimmed, lower-cased) at the route boundary (#116). */
    fun create(
        token: VerifiedFirebaseToken,
        email: String,
    ): Either<ServiceError, Invite> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val invite =
                invites.createOrRotate(
                    email = email,
                    invitedBy = adminId,
                    expiresAt = LocalDateTime.now().plusDays(INVITE_TTL_DAYS),
                )
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.INVITE_CREATED,
                        entityType = AuditEntityType.INVITE,
                        entityId = invite.id,
                        summary = "Invited ${invite.email}",
                        details = mapOf("email" to invite.email, "status" to invite.status.name),
                    ),
            )
            invite
        }

    fun list(
        token: VerifiedFirebaseToken,
        limit: Int,
        offset: Int,
        status: InviteStatus? = null,
    ): Either<ServiceError, InvitePage> =
        either {
            requireAdmin(token = token).bind()
            val (items, total) =
                invites.list(
                    limit = limit.coerceIn(minimumValue = 1, maximumValue = MAX_PAGE_SIZE),
                    offset = offset.coerceAtLeast(minimumValue = 0),
                    status = status,
                )
            InvitePage(items = items, total = total.toInt())
        }

    fun revoke(
        token: VerifiedFirebaseToken,
        id: UUID,
    ): Either<ServiceError, Unit> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val revoked = invites.revoke(id = id).bind()
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.INVITE_REVOKED,
                        entityType = AuditEntityType.INVITE,
                        entityId = id,
                        summary = "Revoked invite ${revoked.email}",
                        details = mapOf("email" to revoked.email, "inviteId" to id.toString()),
                    ),
            )
        }

    /** ADMINISTRATOR-only access; returns the caller's id (the audit actor). */
    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        val isAdmin = caller != null && caller.capabilities.contains(element = Capability.ADMINISTRATOR)
        return if (caller == null || !isAdmin) ServiceError.Forbidden().left() else caller.id.right()
    }
}
