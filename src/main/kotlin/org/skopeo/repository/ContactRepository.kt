package org.skopeo.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.model.Contact
import org.skopeo.model.ContactSource
import org.skopeo.model.ContactType
import org.skopeo.model.VerificationMethod
import org.skopeo.model.VerificationStatus
import java.time.LocalDateTime
import java.util.UUID

/**
 * Row-level persistence for individual [Contact]s. Contacts are written here as
 * MANUAL/PENDING (the user/admin adds them by hand); verification state is set
 * separately via [setVerification]. The DB enforces one-per-type and globally-unique
 * verified values — those surface as unique-violation SQL exceptions for the service
 * layer to translate into 409s.
 */
class ContactRepository {
    fun listByUser(userId: UUID): List<Contact> =
        transaction {
            ContactInformationTable
                .selectAll()
                .where { ContactInformationTable.userId eq userId }
                .map { it.toContact() }
        }

    fun findById(id: UUID): Contact? =
        transaction {
            ContactInformationTable
                .selectAll()
                .where { ContactInformationTable.id eq id }
                .singleOrNull()
                ?.toContact()
        }

    fun create(
        userId: UUID,
        type: ContactType,
        value: String,
        isPrimary: Boolean,
    ): Contact =
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
            loadById(id.value)
        }

    /** Enable or disable a contact (the append-only alternative to editing). */
    fun setActive(
        id: UUID,
        active: Boolean,
        disabledAt: LocalDateTime?,
    ): Contact? =
        transaction {
            val updated =
                ContactInformationTable.update({ ContactInformationTable.id eq id }) {
                    it[isActive] = active
                    it[ContactInformationTable.disabledAt] = disabledAt
                }
            if (updated == 0) null else loadById(id)
        }

    /**
     * Set verification state (the ADMINISTRATOR action). VERIFIED stamps the method,
     * time, and verifying admin; any other status clears those audit fields.
     */
    fun setVerification(
        id: UUID,
        status: VerificationStatus,
        method: VerificationMethod?,
        verifiedBy: UUID?,
        verifiedAt: LocalDateTime?,
    ): Contact? =
        transaction {
            val verified = status == VerificationStatus.VERIFIED
            val methodName = if (verified) method?.name else null
            val whenVerified = if (verified) verifiedAt else null
            val whoVerified = if (verified) verifiedBy else null
            val updated =
                ContactInformationTable.update({ ContactInformationTable.id eq id }) {
                    it[verificationStatus] = status.name
                    it[verificationMethod] = methodName
                    it[ContactInformationTable.verifiedAt] = whenVerified
                    it[ContactInformationTable.verifiedBy] = whoVerified
                }
            if (updated == 0) null else loadById(id)
        }

    private fun loadById(id: UUID): Contact =
        ContactInformationTable
            .selectAll()
            .where { ContactInformationTable.id eq id }
            .single()
            .toContact()
}

/** Map a contact_information row to the [Contact] domain type. Shared with [UserRepository]. */
internal fun ResultRow.toContact(): Contact =
    Contact(
        id = this[ContactInformationTable.id].value,
        userId = this[ContactInformationTable.userId].value,
        type = ContactType.valueOf(this[ContactInformationTable.contactType]),
        value = this[ContactInformationTable.value],
        source = ContactSource.valueOf(this[ContactInformationTable.contactSource]),
        status = VerificationStatus.valueOf(this[ContactInformationTable.verificationStatus]),
        method = this[ContactInformationTable.verificationMethod]?.let(VerificationMethod::valueOf),
        isPrimary = this[ContactInformationTable.isPrimary],
        isActive = this[ContactInformationTable.isActive],
        verifiedAt = this[ContactInformationTable.verifiedAt],
        verifiedBy = this[ContactInformationTable.verifiedBy]?.value,
        disabledAt = this[ContactInformationTable.disabledAt],
    )
