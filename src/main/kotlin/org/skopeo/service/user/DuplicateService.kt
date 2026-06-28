// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.Capability
import org.skopeo.model.User
import org.skopeo.repository.UserRepository
import org.skopeo.service.ConflictException
import org.skopeo.service.audit.AuditService
import java.util.UUID

/**
 * Duplicate-profile rectification (issue #124) — ADMINISTRATOR-only. One person must have one profile,
 * so an admin designates a canonical ("true") account and marks the others as duplicates: each duplicate
 * is disabled and pointed at the canonical (it drops out of search, and its public profile links to the
 * canonical). Records are retained, ratings are NOT consolidated, and every mark/un-mark is audit-logged
 * and reversible.
 */
class DuplicateService(
    private val users: UserRepository = UserRepository(),
    private val audit: AuditService = AuditService(),
) {
    /** Mark [duplicateIds] as disabled duplicates of [canonicalId]. */
    fun markDuplicates(
        token: VerifiedFirebaseToken,
        canonicalId: UUID,
        duplicateIds: List<UUID>,
    ): List<User> {
        val adminId = requireAdmin(token = token)
        val canonical = users.findById(id = canonicalId) ?: throw UserNotFoundException(id = canonicalId)
        require(value = duplicateIds.isNotEmpty()) { "At least one duplicate is required" }
        require(value = duplicateIds.toSet().size == duplicateIds.size) { "Duplicate ids must be distinct" }
        require(value = canonicalId !in duplicateIds) { "A profile cannot be a duplicate of itself" }
        if (canonical.canonicalUserId != null) {
            throw ConflictException(message = "The canonical account is itself a duplicate")
        }
        val targets = duplicateIds.map { resolveMarkable(id = it) }

        users.markDuplicates(canonicalId = canonicalId, duplicateIds = duplicateIds)
        targets.forEach { target ->
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.USER_MARKED_DUPLICATE,
                        entityType = AuditEntityType.USER,
                        entityId = target.id,
                        summary = "Marked ${target.publicCode} as a duplicate of ${canonical.publicCode}",
                        details =
                            mapOf(
                                "canonicalUserId" to canonicalId.toString(),
                                "canonicalPublicCode" to canonical.publicCode,
                            ),
                    ),
            )
        }
        return users.findDuplicatesOf(canonicalId = canonicalId)
    }

    /** Reverse a duplicate marking on [id]: reactivate and clear its canonical pointer. */
    fun restore(
        token: VerifiedFirebaseToken,
        id: UUID,
    ) {
        val adminId = requireAdmin(token = token)
        val target = users.findById(id = id) ?: throw UserNotFoundException(id = id)
        if (target.canonicalUserId == null) {
            throw ConflictException(message = "User ${target.publicCode} is not marked as a duplicate")
        }
        users.restoreDuplicate(id = id)
        audit.record(
            write =
                AuditWrite(
                    actorUserId = adminId,
                    action = AuditAction.USER_UNMARKED_DUPLICATE,
                    entityType = AuditEntityType.USER,
                    entityId = target.id,
                    summary = "Restored ${target.publicCode} from duplicate status",
                    details =
                        mapOf(
                            "publicCode" to target.publicCode,
                            "previousCanonicalUserId" to target.canonicalUserId.toString(),
                        ),
                ),
        )
    }

    /** The disabled duplicates currently pointing at [canonicalId] — for the admin un-mark view. */
    fun duplicatesOf(
        token: VerifiedFirebaseToken,
        canonicalId: UUID,
    ): List<User> {
        requireAdmin(token = token)
        users.findById(id = canonicalId) ?: throw UserNotFoundException(id = canonicalId)
        return users.findDuplicatesOf(canonicalId = canonicalId)
    }

    /** A target must exist and not itself already be a canonical for other duplicates. */
    private fun resolveMarkable(id: UUID): User {
        val target = users.findById(id = id) ?: throw UserNotFoundException(id = id)
        if (users.findDuplicatesOf(canonicalId = id).isNotEmpty()) {
            throw ConflictException(message = "User ${target.publicCode} is itself a canonical account for other duplicates")
        }
        return target
    }

    private fun requireAdmin(token: VerifiedFirebaseToken): UUID {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        if (caller == null || !caller.capabilities.contains(element = Capability.ADMINISTRATOR)) throw ForbiddenException()
        return caller.id
    }
}
