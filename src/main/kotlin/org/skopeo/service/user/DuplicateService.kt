// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.Capability
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import java.util.UUID

/**
 * Duplicate-profile rectification (issue #124) — ADMINISTRATOR-only. One person must have one profile,
 * so an admin designates a canonical ("true") account and marks the others as duplicates: each duplicate
 * is disabled and pointed at the canonical (it drops out of search, and its public profile links to the
 * canonical). Records are retained, ratings are NOT consolidated, and every mark/un-mark is audit-logged
 * and reversible.
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
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
    ): Either<ServiceError, List<User>> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val canonical = users.findById(id = canonicalId).bind()
            ensure(condition = duplicateIds.isNotEmpty()) { ServiceError.Validation(message = "At least one duplicate is required") }
            ensure(
                condition = duplicateIds.toSet().size == duplicateIds.size,
            ) { ServiceError.Validation(message = "Duplicate ids must be distinct") }
            ensure(
                condition = canonicalId !in duplicateIds,
            ) { ServiceError.Validation(message = "A profile cannot be a duplicate of itself") }
            ensure(condition = canonical.canonicalUserId == null) {
                ServiceError.Conflict(message = "The canonical account is itself a duplicate")
            }
            val targets = duplicateIds.map { resolveMarkable(id = it).bind() }

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
            users.findDuplicatesOf(canonicalId = canonicalId)
        }

    /** Reverse a duplicate marking on [id]: reactivate and clear its canonical pointer. */
    fun restore(
        token: VerifiedFirebaseToken,
        id: UUID,
    ): Either<ServiceError, Unit> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val target = users.findById(id = id).bind()
            ensure(condition = target.canonicalUserId != null) {
                ServiceError.Conflict(message = "User ${target.publicCode} is not marked as a duplicate")
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
    ): Either<ServiceError, List<User>> =
        either {
            requireAdmin(token = token).bind()
            users.findById(id = canonicalId).bind()
            users.findDuplicatesOf(canonicalId = canonicalId)
        }

    /** A target must exist and not itself already be a canonical for other duplicates. */
    private fun resolveMarkable(id: UUID): Either<ServiceError, User> =
        either {
            val target = users.findById(id = id).bind()
            ensure(condition = users.findDuplicatesOf(canonicalId = id).isEmpty()) {
                ServiceError.Conflict(message = "User ${target.publicCode} is itself a canonical account for other duplicates")
            }
            target
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
