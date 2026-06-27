// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.capability

import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.Capability
import org.skopeo.model.CapabilityGrant
import org.skopeo.repository.CapabilityRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.ConflictException
import org.skopeo.service.audit.AuditService
import org.skopeo.service.user.ForbiddenException
import org.skopeo.service.user.UserNotFoundException
import org.skopeo.service.user.VerifiedFirebaseToken
import java.time.LocalDateTime
import java.util.UUID

/**
 * Manage users' capabilities (roles). The entire API is ADMINISTRATOR-only — a user never
 * elevates themselves. Grants are append-only (re-granting after a revoke is a fresh row),
 * and several guardrails protect against lockout and accidental self-demotion.
 */
class CapabilityService(
    private val capabilities: CapabilityRepository = CapabilityRepository(),
    private val users: UserRepository = UserRepository(),
    private val audit: AuditService = AuditService(),
) {
    /** Outcome of a grant: [created] distinguishes a fresh grant (201) from an idempotent hit (200). */
    data class Granted(
        val grant: CapabilityGrant,
        val created: Boolean,
    )

    fun list(
        token: VerifiedFirebaseToken,
        userId: UUID,
    ): List<CapabilityGrant> {
        requireAdmin(token = token)
        requireUserExists(userId = userId)
        return capabilities.listByUser(userId = userId)
    }

    fun grant(
        token: VerifiedFirebaseToken,
        userId: UUID,
        capability: Capability,
    ): Granted {
        val adminId = requireAdmin(token = token)
        requireUserExists(userId = userId)
        capabilities.findActive(userId = userId, capability = capability)?.let { return Granted(grant = it, created = false) }
        val grant = capabilities.grant(userId = userId, capability = capability, grantedBy = adminId)
        audit.record(
            write =
                AuditWrite(
                    actorUserId = adminId,
                    action = AuditAction.CAPABILITY_GRANTED,
                    entityType = AuditEntityType.CAPABILITY,
                    entityId = userId,
                    summary = "Granted ${capability.name} role",
                    details = mapOf("userId" to userId.toString(), "capability" to capability.name),
                ),
        )
        return Granted(grant = grant, created = true)
    }

    @Suppress("ThrowsCount") // each throw is a distinct guardrail with its own status code
    fun revoke(
        token: VerifiedFirebaseToken,
        userId: UUID,
        capability: Capability,
    ) {
        val adminId = requireAdmin(token = token)
        requireUserExists(userId = userId)

        if (capability == Capability.PLAYER) {
            throw ConflictException(message = "The PLAYER role cannot be revoked")
        }
        capabilities.findActive(userId = userId, capability = capability)
            ?: throw CapabilityNotFoundException(userId = userId, capability = capability)
        if (capability == Capability.ADMINISTRATOR) {
            // Last-admin check precedes the self-check: dropping to zero admins is only possible
            // by revoking the sole (necessarily one's own) admin grant.
            if (capabilities.countActiveAdministrators() <= 1) {
                throw ConflictException(message = "Cannot revoke the last ADMINISTRATOR")
            }
            if (userId == adminId) {
                throw ForbiddenException(message = "You cannot revoke your own ADMINISTRATOR role")
            }
        }

        capabilities.revoke(userId = userId, capability = capability, revokedBy = adminId, revokedAt = LocalDateTime.now())
        audit.record(
            write =
                AuditWrite(
                    actorUserId = adminId,
                    action = AuditAction.CAPABILITY_REVOKED,
                    entityType = AuditEntityType.CAPABILITY,
                    entityId = userId,
                    summary = "Revoked ${capability.name} role",
                    details = mapOf("userId" to userId.toString(), "capability" to capability.name),
                ),
        )
    }

    private fun requireUserExists(userId: UUID) {
        users.findById(id = userId) ?: throw UserNotFoundException(id = userId)
    }

    /** Every capability operation requires the caller to be an ADMINISTRATOR; returns their id. */
    private fun requireAdmin(token: VerifiedFirebaseToken): UUID {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        if (caller == null || !caller.capabilities.contains(element = Capability.ADMINISTRATOR)) throw ForbiddenException()
        return caller.id
    }
}
