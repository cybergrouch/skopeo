// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.contact

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.Capability
import org.skopeo.model.Contact
import org.skopeo.model.ContactType
import org.skopeo.model.VerificationMethod
import org.skopeo.model.VerificationStatus
import org.skopeo.repository.ContactRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import org.skopeo.service.user.ForbiddenException
import org.skopeo.service.user.UserNotFoundException
import org.skopeo.service.user.VerifiedFirebaseToken
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.UUID

private const val PG_UNIQUE_VIOLATION = "23505"

/**
 * Manage a user's contacts. Editing the address is self-or-ADMINISTRATOR (and resets
 * any verification); changing the verification state is ADMINISTRATOR-only — the
 * manual stand-in for automated OTP. The DB's uniqueness rules surface as 409s.
 */
class ContactService(
    private val contacts: ContactRepository = ContactRepository(),
    private val users: UserRepository = UserRepository(),
    private val audit: AuditService = AuditService(),
) {
    fun list(
        token: VerifiedFirebaseToken,
        userId: UUID,
    ): List<Contact> {
        requireUserExists(userId = userId)
        requireUserAccess(token = token, userId = userId)
        return contacts.listByUser(userId = userId)
    }

    fun get(
        token: VerifiedFirebaseToken,
        userId: UUID,
        contactId: UUID,
    ): Contact {
        val contact = locate(userId = userId, contactId = contactId)
        requireUserAccess(token = token, userId = userId)
        return contact
    }

    fun create(
        token: VerifiedFirebaseToken,
        userId: UUID,
        type: ContactType,
        value: String,
        isPrimary: Boolean,
    ): Contact {
        requireUserExists(userId = userId)
        val actor = requireUserAccess(token = token, userId = userId)
        val contact =
            conflictAware(message = "A ${type.name} contact already exists for this user") {
                contacts.create(userId = userId, type = type, value = value, isPrimary = isPrimary)
            }
        audit.record(
            write =
                AuditWrite(
                    actorUserId = actor,
                    action = AuditAction.CONTACT_ADDED,
                    entityType = AuditEntityType.USER,
                    entityId = userId,
                    summary = "Added ${type.name} $value",
                    details = mapOf("contactType" to type.name, "value" to value),
                ),
        )
        return contact
    }

    /**
     * Enable or disable a contact — the append-only alternative to editing. Enabling a
     * contact while another of the same type is already active is a conflict (409).
     */
    fun setActive(
        token: VerifiedFirebaseToken,
        userId: UUID,
        contactId: UUID,
        active: Boolean,
    ): Contact {
        val contact = locate(userId = userId, contactId = contactId)
        val actor = requireUserAccess(token = token, userId = userId)
        val disabledAt = if (active) null else LocalDateTime.now()
        // locate() already proved the contact exists, so the update can't be a no-op; conflictAware
        // still surfaces the "another active contact of that type" conflict when re-enabling.
        conflictAware(message = "Another active contact of that type already exists") {
            contacts.setActive(id = contactId, active = active, disabledAt = disabledAt)
        }
        audit.record(
            write =
                AuditWrite(
                    actorUserId = actor,
                    action = AuditAction.CONTACT_UPDATED,
                    entityType = AuditEntityType.USER,
                    entityId = userId,
                    summary = "${if (active) "Enabled" else "Disabled"} ${contact.type.name} ${contact.value}",
                    details = mapOf("contactId" to contactId.toString(), "active" to active.toString()),
                ),
        )
        return contact.copy(isActive = active, disabledAt = disabledAt)
    }

    /** The ADMINISTRATOR-only verification action; records who verified and when. */
    fun setVerification(
        token: VerifiedFirebaseToken,
        userId: UUID,
        contactId: UUID,
        status: VerificationStatus,
        method: VerificationMethod?,
    ): Contact {
        val contact = locate(userId = userId, contactId = contactId)
        val adminId = requireAdmin(token = token)
        require(value = contact.isActive) { "Cannot change verification of a disabled contact" }
        // Business rule: a VERIFIED status defaults to ADMIN_OVERRIDE; a non-verified status has no method.
        val resolvedMethod = if (status == VerificationStatus.VERIFIED) method ?: VerificationMethod.ADMIN_OVERRIDE else null
        return conflictAware(message = "This value is already verified for another account") {
            contacts.setVerification(
                id = contactId,
                status = status,
                method = resolvedMethod,
                verifiedBy = adminId,
                verifiedAt = LocalDateTime.now(),
            )
        } ?: throw ContactNotFoundException(id = contactId)
    }

    private fun locate(
        userId: UUID,
        contactId: UUID,
    ): Contact {
        val contact = contacts.findById(id = contactId)
        if (contact == null || contact.userId != userId) throw ContactNotFoundException(id = contactId)
        return contact
    }

    private fun requireUserExists(userId: UUID) {
        users.findById(id = userId) ?: throw UserNotFoundException(id = userId)
    }

    /** Self-or-ADMINISTRATOR access; returns the caller's id (the audit actor). */
    private fun requireUserAccess(
        token: VerifiedFirebaseToken,
        userId: UUID,
    ): UUID {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        val isSelf = caller?.id == userId
        val isAdmin = caller?.capabilities?.contains(element = Capability.ADMINISTRATOR) == true
        if (caller == null || (!isSelf && !isAdmin)) throw ForbiddenException()
        return caller.id
    }

    private fun requireAdmin(token: VerifiedFirebaseToken): UUID {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        if (caller == null || !caller.capabilities.contains(element = Capability.ADMINISTRATOR)) throw ForbiddenException()
        return caller.id
    }
}

private fun <T> conflictAware(
    message: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: ExposedSQLException) {
        if (isUniqueViolation(e = e)) throw ContactConflictException(message = message) else throw e
    }

private fun isUniqueViolation(e: ExposedSQLException): Boolean =
    generateSequence<Throwable>(seed = e) { it.cause }.any { (it as? SQLException)?.sqlState == PG_UNIQUE_VIOLATION }
