// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.capability

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import org.skopeo.common.dto.capability.CapabilityResponse
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.dto.capability.toResponse
import org.skopeo.domain.mapper.entity.capability.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuditEntityType
import org.skopeo.domain.model.AuditWrite
import org.skopeo.domain.model.ContactType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.VerificationStatus
import org.skopeo.domain.service.audit.AuditService
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.CapabilityRepository
import org.skopeo.repository.UserRepository
import java.time.LocalDateTime
import java.util.UUID

/**
 * Manage users' capabilities (roles). The entire API is ADMINISTRATOR-only — a user never
 * elevates themselves. Grants are append-only (re-granting after a revoke is a fresh row),
 * and several guardrails protect against lockout and accidental self-demotion.
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
class CapabilityService(
    private val capabilities: CapabilityRepository = CapabilityRepository(),
    private val users: UserRepository = UserRepository(),
    private val audit: AuditService = AuditService(),
    // The bootstrap ADMINISTRATOR allowlist (config `admin.emails`), normalized lowercase/trimmed.
    // A user whose verified email is currently on it is the break-glass admin and cannot be
    // demoted via the API (#194). Empty ⇒ no protected admins. See ADMIN_BOOTSTRAP.md.
    private val adminEmails: Set<String> = emptySet(),
) {
    /** Outcome of a grant: [created] distinguishes a fresh grant (201) from an idempotent hit (200). */
    data class Granted(
        val grant: CapabilityResponse,
        val created: Boolean,
    )

    fun list(
        token: VerifiedFirebaseToken,
        userId: UUID,
    ): Either<ServiceError, List<CapabilityResponse>> =
        either {
            requireAdmin(token = token).bind()
            requireUserExists(userId = userId).bind()
            capabilities.listByUser(userId = userId).map { it.toDomain().toResponse() }
        }

    fun grant(
        token: VerifiedFirebaseToken,
        userId: UUID,
        capabilityRaw: String,
    ): Either<ServiceError, Granted> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val capability = capabilityOf(raw = capabilityRaw)
            requireUserExists(userId = userId).bind()
            val existing = capabilities.findActive(userId = userId, capability = capability)
            if (existing != null) {
                Granted(grant = existing.toDomain().toResponse(), created = false)
            } else {
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
                Granted(grant = grant.toDomain().toResponse(), created = true)
            }
        }

    fun revoke(
        token: VerifiedFirebaseToken,
        userId: UUID,
        capabilityRaw: String,
    ): Either<ServiceError, Unit> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val capability = capabilityOf(raw = capabilityRaw)
            val target = users.findById(id = userId).bind().toDomain()

            ensure(condition = capability != Capability.PLAYER) {
                ServiceError.Conflict(message = "The PLAYER role cannot be revoked")
            }
            ensure(condition = capabilities.findActive(userId = userId, capability = capability) != null) {
                ServiceError.NotFound(message = "User $userId does not hold an active $capability capability")
            }
            if (capability == Capability.ADMINISTRATOR) {
                // The bootstrap (break-glass) admin is protected first — they must stay an admin
                // regardless of who attempts the revoke. Remove their email from the allowlist to
                // make the role revocable again (ADMIN_BOOTSTRAP.md).
                ensure(condition = !isBootstrapAdmin(user = target)) {
                    ServiceError.Conflict(message = "Cannot revoke a bootstrap administrator")
                }
                // Last-admin check precedes the self-check: dropping to zero admins is only possible
                // by revoking the sole (necessarily one's own) admin grant.
                ensure(condition = capabilities.countActiveAdministrators() > 1) {
                    ServiceError.Conflict(message = "Cannot revoke the last ADMINISTRATOR")
                }
                ensure(condition = userId != adminId) {
                    ServiceError.Forbidden(message = "You cannot revoke your own ADMINISTRATOR role")
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

    /** Parse a raw `capability` name to a [Capability]; an unknown value is a 400 (`ServiceError.Validation`). */
    private fun Raise<ServiceError>.capabilityOf(raw: String): Capability =
        Capability.entries.find { it.name == raw.uppercase() }
            ?: raise(
                r =
                    ServiceError.Validation(
                        message = "Unknown capability '$raw'; expected one of ${Capability.entries.joinToString { it.name }}",
                    ),
            )

    private fun requireUserExists(userId: UUID): Either<ServiceError, Unit> = users.findById(id = userId).map { }

    /**
     * Whether [user] is a current bootstrap administrator: a verified email on the live allowlist.
     * Mirrors the verified-email gate used at sign-up/login (TokenMapping.isBootstrapAdmin). Empty
     * allowlist ⇒ never true.
     */
    private fun isBootstrapAdmin(user: User): Boolean =
        adminEmails.isNotEmpty() &&
            user.contacts.any { contact ->
                contact.isActive &&
                    contact.type == ContactType.EMAIL &&
                    contact.status == VerificationStatus.VERIFIED &&
                    contact.value.trim().lowercase() in adminEmails
            }

    /** Every capability operation requires the caller to be an ADMINISTRATOR; returns their id. */
    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
        return if (caller == null || !caller.capabilities.contains(element = Capability.ADMINISTRATOR)) {
            ServiceError.Forbidden().left()
        } else {
            caller.id.right()
        }
    }
}
