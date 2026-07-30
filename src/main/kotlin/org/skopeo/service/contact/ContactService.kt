// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.contact

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import org.skopeo.dto.contact.ContactResponse
import org.skopeo.error.ServiceError
import org.skopeo.mapper.contact.toResponse
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.Capability
import org.skopeo.model.Contact
import org.skopeo.model.ContactType
import org.skopeo.model.DuplicateSignal
import org.skopeo.model.VerificationMethod
import org.skopeo.model.VerificationStatus
import org.skopeo.repository.ContactRepository
import org.skopeo.repository.DuplicateCandidateRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import org.skopeo.service.user.VerifiedFirebaseToken
import java.time.LocalDateTime
import java.util.UUID

/**
 * Manage a user's contacts. Editing the address is self-or-ADMINISTRATOR (and resets
 * any verification); changing the verification state is ADMINISTRATOR-only — the
 * manual stand-in for automated OTP. The DB's uniqueness rules surface as 409s.
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
@Suppress("TooManyFunctions") // Cohesive contact CRUD + the three query-param enum parsers (#597 routes↛model).
class ContactService(
    private val contacts: ContactRepository = ContactRepository(),
    private val users: UserRepository = UserRepository(),
    private val audit: AuditService = AuditService(),
    private val candidates: DuplicateCandidateRepository = DuplicateCandidateRepository(),
) {
    fun list(
        token: VerifiedFirebaseToken,
        userId: UUID,
    ): Either<ServiceError, List<ContactResponse>> =
        either {
            requireUserExists(userId = userId).bind()
            requireUserAccess(token = token, userId = userId).bind()
            contacts.listByUser(userId = userId).map { it.toResponse() }
        }

    fun get(
        token: VerifiedFirebaseToken,
        userId: UUID,
        contactId: UUID,
    ): Either<ServiceError, ContactResponse> =
        either {
            val contact = locate(userId = userId, contactId = contactId).bind()
            requireUserAccess(token = token, userId = userId).bind()
            contact.toResponse()
        }

    fun create(
        token: VerifiedFirebaseToken,
        userId: UUID,
        typeRaw: String,
        value: String,
        isPrimary: Boolean,
    ): Either<ServiceError, ContactResponse> =
        either {
            requireUserExists(userId = userId).bind()
            val actor = requireUserAccess(token = token, userId = userId).bind()
            val type = parseType(raw = typeRaw).bind()
            val contact =
                contacts
                    .create(
                        userId = userId,
                        type = type,
                        value = value,
                        isPrimary = isPrimary,
                    ).bind()
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
            if (type == ContactType.PHONE) flagPhoneDuplicates(newUserId = userId, value = value)
            contact.toResponse()
        }

    /**
     * Duplicate-account detection (#126): if this new phone matches another active user's (after
     * normalization), raise a candidate for admin review. Flag-and-allow — the contact add is never
     * blocked, and a shared number just surfaces a candidate (admins can dismiss legitimate ones).
     */
    private fun flagPhoneDuplicates(
        newUserId: UUID,
        value: String,
    ) {
        val normalized = normalizePhone(raw = value)
        contacts
            .activePhonesOfOtherActiveUsers(excludeUserId = newUserId)
            .filter { normalizePhone(raw = it.value) == normalized }
            .map { it.userId }
            .distinct()
            .forEach { otherUserId ->
                candidates.flag(
                    userAId = newUserId,
                    userBId = otherUserId,
                    signal = DuplicateSignal.DUPLICATE_PHONE,
                    detail = value,
                    flaggedBy = null,
                )
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
    ): Either<ServiceError, ContactResponse> =
        either {
            val contact = locate(userId = userId, contactId = contactId).bind()
            val actor = requireUserAccess(token = token, userId = userId).bind()
            val disabledAt = if (active) null else LocalDateTime.now()
            // locate() already proved the contact exists, so the update can't be a no-op; the repository
            // still surfaces the "another active contact of that type" conflict when re-enabling.
            contacts
                .setActive(
                    id = contactId,
                    active = active,
                    disabledAt = disabledAt,
                ).bind()
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
            contact.copy(isActive = active, disabledAt = disabledAt).toResponse()
        }

    /** The ADMINISTRATOR-only verification action; records who verified and when. */
    fun setVerification(
        token: VerifiedFirebaseToken,
        userId: UUID,
        contactId: UUID,
        statusRaw: String,
        methodRaw: String?,
    ): Either<ServiceError, ContactResponse> =
        either {
            val contact = locate(userId = userId, contactId = contactId).bind()
            val adminId = requireAdmin(token = token).bind()
            val status = parseStatus(raw = statusRaw).bind()
            val method = methodRaw?.let { raw -> parseMethod(raw = raw).bind() }
            ensure(condition = contact.isActive) {
                ServiceError.Validation(message = "Cannot change verification of a disabled contact")
            }
            // Business rule: a VERIFIED status defaults to ADMIN_OVERRIDE; a non-verified status has no method.
            val resolvedMethod = if (status == VerificationStatus.VERIFIED) method ?: VerificationMethod.ADMIN_OVERRIDE else null
            contacts
                .setVerification(
                    id = contactId,
                    status = status,
                    method = resolvedMethod,
                    verifiedBy = adminId,
                    verifiedAt = LocalDateTime.now(),
                ).bind()
                .toResponse()
        }

    private fun locate(
        userId: UUID,
        contactId: UUID,
    ): Either<ServiceError, Contact> =
        either {
            val contact = contacts.findById(id = contactId).bind()
            ensure(condition = contact.userId == userId) { ServiceError.NotFound(message = "Contact $contactId not found") }
            contact
        }

    private fun requireUserExists(userId: UUID): Either<ServiceError, Unit> = users.findById(id = userId).map { }

    /** Self-or-ADMINISTRATOR access; returns the caller's id (the audit actor). */
    private fun requireUserAccess(
        token: VerifiedFirebaseToken,
        userId: UUID,
    ): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        if (caller == null) return ServiceError.Forbidden().left()
        val isSelf = caller.id == userId
        val isAdmin = caller.capabilities.contains(element = Capability.ADMINISTRATOR)
        return if (!isSelf && !isAdmin) ServiceError.Forbidden().left() else caller.id.right()
    }

    private fun parseType(raw: String): Either<ServiceError, ContactType> {
        val allowedTypes = ContactType.entries.joinToString { it.name }
        return ContactType.entries.find { it.name == raw }?.right()
            ?: ServiceError.Validation(message = "Invalid type '$raw'; expected one of $allowedTypes").left()
    }

    private fun parseStatus(raw: String): Either<ServiceError, VerificationStatus> {
        val allowedStatuses = VerificationStatus.entries.joinToString { it.name }
        return VerificationStatus.entries.find { it.name == raw }?.right()
            ?: ServiceError.Validation(message = "Invalid status '$raw'; expected one of $allowedStatuses").left()
    }

    private fun parseMethod(raw: String): Either<ServiceError, VerificationMethod> {
        val allowedMethods = VerificationMethod.entries.joinToString { it.name }
        return VerificationMethod.entries.find { it.name == raw }?.right()
            ?: ServiceError.Validation(message = "Invalid method '$raw'; expected one of $allowedMethods").left()
    }

    /** ADMINISTRATOR-only access; returns the caller's id (the audit actor). */
    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        val isAdmin = caller != null && caller.capabilities.contains(element = Capability.ADMINISTRATOR)
        return if (caller == null || !isAdmin) ServiceError.Forbidden().left() else caller.id.right()
    }
}

/**
 * Normalize a phone number for duplicate detection (#126): keep only digits, preserving a single leading
 * '+', so "+63 917-000" and "+63917000" compare equal. Comparison-only — the stored value is unchanged.
 */
internal fun normalizePhone(raw: String): String {
    val trimmed = raw.trim()
    val prefix = if (trimmed.startsWith(prefix = "+")) "+" else ""
    return prefix + trimmed.filter { it.isDigit() }
}
