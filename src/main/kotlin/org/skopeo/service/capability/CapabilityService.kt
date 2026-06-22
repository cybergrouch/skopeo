package org.skopeo.service.capability

import org.skopeo.model.Capability
import org.skopeo.model.CapabilityGrant
import org.skopeo.repository.CapabilityRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.ConflictException
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
        requireAdmin(token)
        requireUserExists(userId)
        return capabilities.listByUser(userId)
    }

    fun grant(
        token: VerifiedFirebaseToken,
        userId: UUID,
        capabilityName: String,
    ): Granted {
        val adminId = requireAdmin(token)
        requireUserExists(userId)
        val capability = parseCapability(capabilityName)
        capabilities.findActive(userId = userId, capability = capability)?.let { return Granted(grant = it, created = false) }
        return Granted(grant = capabilities.grant(userId = userId, capability = capability, grantedBy = adminId), created = true)
    }

    @Suppress("ThrowsCount") // each throw is a distinct guardrail with its own status code
    fun revoke(
        token: VerifiedFirebaseToken,
        userId: UUID,
        capabilityName: String,
    ) {
        val adminId = requireAdmin(token)
        requireUserExists(userId)
        val capability = parseCapability(capabilityName)

        if (capability == Capability.PLAYER) {
            throw ConflictException("The PLAYER role cannot be revoked")
        }
        capabilities.findActive(userId = userId, capability = capability)
            ?: throw CapabilityNotFoundException(userId, capability)
        if (capability == Capability.ADMINISTRATOR) {
            // Last-admin check precedes the self-check: dropping to zero admins is only possible
            // by revoking the sole (necessarily one's own) admin grant.
            if (capabilities.countActiveAdministrators() <= 1) {
                throw ConflictException("Cannot revoke the last ADMINISTRATOR")
            }
            if (userId == adminId) {
                throw ForbiddenException("You cannot revoke your own ADMINISTRATOR role")
            }
        }

        capabilities.revoke(userId = userId, capability = capability, revokedBy = adminId, revokedAt = LocalDateTime.now())
    }

    private fun requireUserExists(userId: UUID) {
        users.findById(userId) ?: throw UserNotFoundException(userId)
    }

    /** Every capability operation requires the caller to be an ADMINISTRATOR; returns their id. */
    private fun requireAdmin(token: VerifiedFirebaseToken): UUID {
        val caller = users.findByFirebaseUid(token.uid)
        if (caller == null || !caller.capabilities.contains(Capability.ADMINISTRATOR)) throw ForbiddenException()
        return caller.id
    }

    private fun parseCapability(value: String): Capability =
        try {
            Capability.valueOf(value)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid capability '$value'", e)
        }
}
