// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.invite

import org.skopeo.model.Capability
import org.skopeo.model.Invite
import org.skopeo.model.InvitePage
import org.skopeo.model.InviteStatus
import org.skopeo.repository.InviteRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.ResourceNotFoundException
import org.skopeo.service.user.ForbiddenException
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
 */
class InviteService(
    private val invites: InviteRepository = InviteRepository(),
    private val users: UserRepository = UserRepository(),
) {
    fun create(
        token: VerifiedFirebaseToken,
        email: String,
    ): Invite {
        val adminId = requireAdmin(token = token)
        return invites.createOrRotate(
            email = normalizeEmail(email = email),
            invitedBy = adminId,
            expiresAt = LocalDateTime.now().plusDays(INVITE_TTL_DAYS),
        )
    }

    fun list(
        token: VerifiedFirebaseToken,
        limit: Int,
        offset: Int,
        status: InviteStatus? = null,
    ): InvitePage {
        requireAdmin(token = token)
        val (items, total) =
            invites.list(
                limit = limit.coerceIn(minimumValue = 1, maximumValue = MAX_PAGE_SIZE),
                offset = offset.coerceAtLeast(minimumValue = 0),
                status = status,
            )
        return InvitePage(items = items, total = total.toInt())
    }

    fun revoke(
        token: VerifiedFirebaseToken,
        id: UUID,
    ) {
        requireAdmin(token = token)
        invites.revoke(id = id) ?: throw ResourceNotFoundException(message = "No invite $id")
    }

    private fun requireAdmin(token: VerifiedFirebaseToken): UUID {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        if (caller == null || !caller.capabilities.contains(element = Capability.ADMINISTRATOR)) throw ForbiddenException()
        return caller.id
    }
}

private fun normalizeEmail(email: String): String {
    val normalized = email.trim().lowercase()
    require(value = normalized.isNotBlank() && normalized.contains(char = '@')) { "A valid email is required" }
    return normalized
}
