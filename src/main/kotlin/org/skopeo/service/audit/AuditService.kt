// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.audit

import org.skopeo.model.AuditAction
import org.skopeo.model.AuditLogPage
import org.skopeo.model.AuditWrite
import org.skopeo.model.Capability
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.ResourceNotFoundException
import org.skopeo.service.user.ForbiddenException
import org.skopeo.service.user.VerifiedFirebaseToken
import java.util.UUID

private const val MAX_PAGE_SIZE = 100

/**
 * The audit-write seam (issue #100): domain services call [record] after a successful action to
 * append a provenance row. Reading the log back is ADMINISTRATOR-only (the trace viewer, #102).
 */
class AuditService(
    private val audit: AuditRepository = AuditRepository(),
    private val users: UserRepository = UserRepository(),
) {
    /** Append a provenance record. Best-effort companion to a domain write; never the primary action. */
    fun record(write: AuditWrite) {
        audit.record(write = write)
    }

    fun list(
        token: VerifiedFirebaseToken,
        action: AuditAction?,
        limit: Int,
        offset: Int,
    ): AuditLogPage {
        requireAdmin(token = token)
        val (items, total) =
            audit.list(
                action = action,
                limit = limit.coerceIn(minimumValue = 1, maximumValue = MAX_PAGE_SIZE),
                offset = offset.coerceAtLeast(minimumValue = 0),
            )
        return AuditLogPage(items = items, total = total.toInt())
    }

    /** Attach/replace an administrator's free-text note on an entry (ADMINISTRATOR only, #100). */
    fun setComment(
        token: VerifiedFirebaseToken,
        id: UUID,
        comment: String?,
    ) {
        requireAdmin(token = token)
        if (!audit.updateComment(id = id, comment = comment?.ifBlank { null })) {
            throw ResourceNotFoundException(message = "No audit entry $id")
        }
    }

    private fun requireAdmin(token: VerifiedFirebaseToken) {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        if (caller == null || !caller.capabilities.contains(element = Capability.ADMINISTRATOR)) throw ForbiddenException()
    }
}
