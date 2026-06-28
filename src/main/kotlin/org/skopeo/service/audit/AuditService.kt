// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.audit

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import org.skopeo.model.AuditCategory
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditEntry
import org.skopeo.model.AuditEntryView
import org.skopeo.model.AuditLogViewPage
import org.skopeo.model.AuditPersonRef
import org.skopeo.model.AuditWrite
import org.skopeo.model.Capability
import org.skopeo.model.ServiceError
import org.skopeo.model.actions
import org.skopeo.model.displayName
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import java.util.UUID

private const val MAX_PAGE_SIZE = 100

// Entity types whose entityId is a user id, so it can be resolved to a name for the viewer.
private val USER_TARGET_TYPES = setOf(AuditEntityType.USER, AuditEntityType.RATING, AuditEntityType.CAPABILITY)

/**
 * The audit-write seam (issue #100): domain services call [record] after a successful action to
 * append a provenance row. Reading the log back — the trace viewer (#102) — is ADMINISTRATOR-only
 * and resolves actor/target user ids to names + player codes for human-readable display.
 *
 * Expected read/comment failures are returned as an [Either] left ([ServiceError], issue #115);
 * [record] stays plain because every domain service calls it on a success path.
 */
class AuditService(
    private val audit: AuditRepository = AuditRepository(),
    private val users: UserRepository = UserRepository(),
) {
    /** Append a provenance record. Best-effort companion to a domain write; never the primary action. */
    fun record(write: AuditWrite) {
        audit.record(write = write)
    }

    /** One page of audit entries, newest first, optionally scoped to a [category] (ADMINISTRATOR only). */
    fun list(
        token: VerifiedFirebaseToken,
        category: AuditCategory?,
        limit: Int,
        offset: Int,
    ): Either<ServiceError, AuditLogViewPage> =
        either {
            requireAdmin(token = token).bind()
            val (items, total) =
                audit.list(
                    actions = category?.actions(),
                    limit = limit.coerceIn(minimumValue = 1, maximumValue = MAX_PAGE_SIZE),
                    offset = offset.coerceAtLeast(minimumValue = 0),
                )
            val refs = resolveRefs(entries = items)
            val views =
                items.map { entry ->
                    AuditEntryView(
                        entry = entry,
                        actor = entry.actorUserId?.let { refs[it] },
                        target = if (entry.entityType in USER_TARGET_TYPES) entry.entityId?.let { refs[it] } else null,
                    )
                }
            AuditLogViewPage(items = views, total = total.toInt())
        }

    /** Attach/replace an administrator's free-text note on an entry (ADMINISTRATOR only, #100). */
    fun setComment(
        token: VerifiedFirebaseToken,
        id: UUID,
        comment: String?,
    ): Either<ServiceError, Unit> =
        either {
            requireAdmin(token = token).bind()
            ensure(condition = audit.updateComment(id = id, comment = comment?.ifBlank { null })) {
                ServiceError.NotFound(message = "No audit entry $id")
            }
        }

    /** Resolve every actor and user-typed target id on the page to a name + code, in one lookup. */
    private fun resolveRefs(entries: List<AuditEntry>): Map<UUID, AuditPersonRef> {
        val ids =
            buildSet {
                entries.forEach { entry ->
                    entry.actorUserId?.let { add(element = it) }
                    if (entry.entityType in USER_TARGET_TYPES) entry.entityId?.let { add(element = it) }
                }
            }
        return users
            .findAllByIds(ids = ids.toList())
            .associate { it.id to AuditPersonRef(userId = it.id, displayName = it.displayName(), publicCode = it.publicCode) }
    }

    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        return if (caller == null || !caller.capabilities.contains(element = Capability.ADMINISTRATOR)) {
            ServiceError.Forbidden().left()
        } else {
            caller.id.right()
        }
    }
}
