// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import arrow.core.Either
import arrow.core.flatten
import arrow.core.left
import arrow.core.right
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.common.error.ServiceError
import org.skopeo.domain.model.ContactSource
import org.skopeo.domain.model.ContactType
import org.skopeo.domain.model.VerificationMethod
import org.skopeo.domain.model.VerificationStatus
import org.skopeo.repository.persistence.ContactEntity
import java.time.LocalDateTime
import java.util.UUID

/**
 * Row-level persistence for individual contacts. Contacts are written here as
 * MANUAL/PENDING (the user/admin adds them by hand); verification state is set
 * separately via [setVerification]. The DB enforces one-per-type and globally-unique
 * verified values — those surface as a [ServiceError.Conflict] via [conflictAware]. Reads
 * return raw [ContactEntity] rows (#633); the service converts them to the domain `Contact`
 * via `mapper.entity`.
 */
class ContactRepository {
    fun listByUser(userId: UUID): List<ContactEntity> =
        transaction {
            ContactInformationTable
                .selectAll()
                .where { ContactInformationTable.userId eq userId }
                .map { it.toContactEntity() }
        }

    fun findById(id: UUID): Either<ServiceError, ContactEntity> =
        transaction {
            val row = ContactInformationTable.selectAll().where { ContactInformationTable.id eq id }.singleOrNull()
            if (row == null) ServiceError.NotFound(message = "Contact $id not found").left() else row.toContactEntity().right()
        }

    /**
     * Add a contact (MANUAL/PENDING). The DB's one-per-type unique index surfaces as a
     * [ServiceError.Conflict].
     */
    fun create(
        userId: UUID,
        type: ContactType,
        value: String,
        isPrimary: Boolean,
    ): Either<ServiceError, ContactEntity> =
        conflictAware(message = "A ${type.name} contact already exists for this user") {
            transaction {
                val id =
                    ContactInformationTable.insertAndGetId {
                        it[ContactInformationTable.userId] = userId
                        it[contactType] = type.name
                        it[ContactInformationTable.value] = value
                        it[ContactInformationTable.isPrimary] = isPrimary
                        it[contactSource] = ContactSource.MANUAL.name
                        it[verificationStatus] = VerificationStatus.PENDING.name
                    }
                loadById(id = id.value)
            }
        }

    /**
     * Active PHONE contacts belonging to OTHER active users — the cross-user duplicate-phone signal
     * (#126). Excludes [excludeUserId] and any contact of a disabled user. Values are returned as
     * stored; the caller normalizes for comparison.
     */
    fun activePhonesOfOtherActiveUsers(excludeUserId: UUID): List<ContactEntity> =
        transaction {
            // contact_information has two FKs to users (user_id, verified_by), so the join column is explicit.
            ContactInformationTable
                .join(
                    otherTable = UsersTable,
                    joinType = JoinType.INNER,
                    onColumn = ContactInformationTable.userId,
                    otherColumn = UsersTable.id,
                ).selectAll()
                .where {
                    (ContactInformationTable.contactType eq ContactType.PHONE.name) and
                        ContactInformationTable.isActive and
                        UsersTable.isActive and
                        (ContactInformationTable.userId neq excludeUserId)
                }.map { it.toContactEntity() }
        }

    /**
     * Whether an ACTIVE user holds an active EMAIL contact equal (case-insensitively) to [email] —
     * the existing-account guard for invites (#132). Disabled users are excluded, so re-inviting a
     * deactivated account is still allowed. Values are compared normalized (trim + lower-case).
     */
    fun activeAccountHasEmail(email: String): Boolean =
        transaction {
            val normalized = email.trim().lowercase()
            ContactInformationTable
                .join(
                    otherTable = UsersTable,
                    joinType = JoinType.INNER,
                    onColumn = ContactInformationTable.userId,
                    otherColumn = UsersTable.id,
                ).selectAll()
                .where {
                    (ContactInformationTable.contactType eq ContactType.EMAIL.name) and
                        ContactInformationTable.isActive and
                        UsersTable.isActive
                }.any { it[ContactInformationTable.value].trim().lowercase() == normalized }
        }

    /**
     * Enable or disable a contact (the append-only alternative to editing). Re-enabling a contact
     * while another of the same type is active violates the one-per-type unique index — surfaced as a
     * [ServiceError.Conflict]; a missing row is a [ServiceError.NotFound].
     */
    fun setActive(
        id: UUID,
        active: Boolean,
        disabledAt: LocalDateTime?,
    ): Either<ServiceError, ContactEntity> =
        conflictAware(message = "Another active contact of that type already exists") {
            transaction {
                val updated =
                    ContactInformationTable.update(where = { ContactInformationTable.id eq id }) {
                        it[isActive] = active
                        it[ContactInformationTable.disabledAt] = disabledAt
                    }
                if (updated == 0) ServiceError.NotFound(message = "Contact $id not found").left() else loadById(id = id).right()
            }
        }.flatten()

    /**
     * Set verification state (the ADMINISTRATOR action). VERIFIED stamps the method,
     * time, and verifying admin; any other status clears those audit fields. The globally-unique
     * verified-value index surfaces as a [ServiceError.Conflict]; a missing row is a [ServiceError.NotFound].
     */
    fun setVerification(
        id: UUID,
        status: VerificationStatus,
        method: VerificationMethod?,
        verifiedBy: UUID?,
        verifiedAt: LocalDateTime?,
    ): Either<ServiceError, ContactEntity> =
        conflictAware(message = "This value is already verified for another account") {
            transaction {
                // VERIFIED stamps method/time/admin; any other status clears all three together.
                val (methodName, whenVerified, whoVerified) =
                    if (status == VerificationStatus.VERIFIED) {
                        Triple(first = method?.name, second = verifiedAt, third = verifiedBy)
                    } else {
                        Triple(first = null, second = null, third = null)
                    }
                val updated =
                    ContactInformationTable.update(where = { ContactInformationTable.id eq id }) {
                        it[verificationStatus] = status.name
                        it[verificationMethod] = methodName
                        it[ContactInformationTable.verifiedAt] = whenVerified
                        it[ContactInformationTable.verifiedBy] = whoVerified
                    }
                if (updated == 0) ServiceError.NotFound(message = "Contact $id not found").left() else loadById(id = id).right()
            }
        }.flatten()

    private fun loadById(id: UUID): ContactEntity =
        ContactInformationTable
            .selectAll()
            .where { ContactInformationTable.id eq id }
            .single()
            .toContactEntity()
}

/** Map a contact_information row to the raw persistence entity (#633) — no derivations, no child rows. Shared with [UserRepository]. */
internal fun ResultRow.toContactEntity(): ContactEntity =
    ContactEntity(
        id = this[ContactInformationTable.id].value,
        userId = this[ContactInformationTable.userId].value,
        type = this[ContactInformationTable.contactType],
        value = this[ContactInformationTable.value],
        source = this[ContactInformationTable.contactSource],
        status = this[ContactInformationTable.verificationStatus],
        method = this[ContactInformationTable.verificationMethod],
        isPrimary = this[ContactInformationTable.isPrimary],
        isActive = this[ContactInformationTable.isActive],
        verifiedAt = this[ContactInformationTable.verifiedAt],
        verifiedBy = this[ContactInformationTable.verifiedBy]?.value,
        disabledAt = this[ContactInformationTable.disabledAt],
    )
