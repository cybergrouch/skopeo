// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.contact

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.skopeo.dto.contact.ContactCreateRequest
import org.skopeo.dto.contact.VerificationRequest
import org.skopeo.model.Capability
import org.skopeo.model.Contact
import org.skopeo.model.ContactType
import org.skopeo.model.VerificationMethod
import org.skopeo.model.VerificationStatus
import org.skopeo.repository.ContactRepository
import org.skopeo.repository.UserRepository
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
) {
    fun list(
        token: VerifiedFirebaseToken,
        userId: UUID,
    ): List<Contact> {
        requireUserExists(userId)
        requireUserAccess(token = token, userId = userId)
        return contacts.listByUser(userId)
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
        request: ContactCreateRequest,
    ): Contact {
        requireUserExists(userId)
        requireUserAccess(token = token, userId = userId)
        val type = parseType(request.type)
        return conflictAware(message = "A ${type.name} contact already exists for this user") {
            contacts.create(userId = userId, type = type, value = request.value, isPrimary = request.isPrimary)
        }
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
        locate(userId = userId, contactId = contactId)
        requireUserAccess(token = token, userId = userId)
        val disabledAt = if (active) null else LocalDateTime.now()
        return conflictAware(message = "Another active contact of that type already exists") {
            contacts.setActive(id = contactId, active = active, disabledAt = disabledAt)
        } ?: throw ContactNotFoundException(contactId)
    }

    /** The ADMINISTRATOR-only verification action; records who verified and when. */
    fun setVerification(
        token: VerifiedFirebaseToken,
        userId: UUID,
        contactId: UUID,
        request: VerificationRequest,
    ): Contact {
        val contact = locate(userId = userId, contactId = contactId)
        val adminId = requireAdmin(token)
        require(contact.isActive) { "Cannot change verification of a disabled contact" }
        val status = parseStatus(request.status)
        val method =
            if (status == VerificationStatus.VERIFIED) {
                request.method?.let(::parseMethod) ?: VerificationMethod.ADMIN_OVERRIDE
            } else {
                null
            }
        return conflictAware(message = "This value is already verified for another account") {
            contacts.setVerification(
                id = contactId,
                status = status,
                method = method,
                verifiedBy = adminId,
                verifiedAt = LocalDateTime.now(),
            )
        } ?: throw ContactNotFoundException(contactId)
    }

    private fun locate(
        userId: UUID,
        contactId: UUID,
    ): Contact {
        val contact = contacts.findById(contactId)
        if (contact == null || contact.userId != userId) throw ContactNotFoundException(contactId)
        return contact
    }

    private fun requireUserExists(userId: UUID) {
        users.findById(userId) ?: throw UserNotFoundException(userId)
    }

    private fun requireUserAccess(
        token: VerifiedFirebaseToken,
        userId: UUID,
    ) {
        val caller = users.findByFirebaseUid(token.uid)
        val isSelf = caller?.id == userId
        val isAdmin = caller?.capabilities?.contains(Capability.ADMINISTRATOR) == true
        if (!isSelf && !isAdmin) throw ForbiddenException()
    }

    private fun requireAdmin(token: VerifiedFirebaseToken): UUID {
        val caller = users.findByFirebaseUid(token.uid)
        if (caller == null || !caller.capabilities.contains(Capability.ADMINISTRATOR)) throw ForbiddenException()
        return caller.id
    }
}

private fun parseType(value: String): ContactType = parseEnum(value) { ContactType.valueOf(it) }

private fun parseStatus(value: String): VerificationStatus = parseEnum(value) { VerificationStatus.valueOf(it) }

private fun parseMethod(value: String): VerificationMethod = parseEnum(value) { VerificationMethod.valueOf(it) }

private fun <T> parseEnum(
    value: String,
    parse: (String) -> T,
): T =
    try {
        parse(value)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid value '$value'", e)
    }

private fun <T> conflictAware(
    message: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: ExposedSQLException) {
        if (isUniqueViolation(e)) throw ContactConflictException(message) else throw e
    }

private fun isUniqueViolation(e: ExposedSQLException): Boolean =
    generateSequence<Throwable>(e) { it.cause }.any { (it as? SQLException)?.sqlState == PG_UNIQUE_VIOLATION }
