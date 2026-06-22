package org.skopeo.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.Contact
import org.skopeo.model.NameType
import org.skopeo.model.ProfilePatch
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import java.util.UUID

/**
 * Persistence for the user aggregate (users + names + identities + contacts +
 * capabilities). Each public method runs in its own transaction; [provision]
 * writes the whole aggregate atomically so a partial sign-up never persists.
 */
class UserRepository {
    fun provision(command: ProvisionUserCommand): User =
        transaction {
            val userId =
                UsersTable.insertAndGetId {
                    it[UsersTable.firebaseUid] = command.firebaseUid
                    it[UsersTable.photoUrl] = command.photoUrl
                    it[UsersTable.dateOfBirth] = command.dateOfBirth
                    it[UsersTable.gender] = command.gender
                    it[UsersTable.city] = command.city
                    it[UsersTable.country] = command.country ?: "PH"
                }

            command.names.forEach { name ->
                UserNamesTable.insert {
                    it[UserNamesTable.userId] = userId
                    it[UserNamesTable.nameType] = name.type.name
                    it[UserNamesTable.value] = name.value
                    it[UserNamesTable.isPrimary] = name.isPrimary
                }
            }

            UserIdentitiesTable.insert {
                it[UserIdentitiesTable.userId] = userId
                it[UserIdentitiesTable.provider] = command.identity.provider.name
                it[UserIdentitiesTable.providerUid] = command.identity.providerUid
                it[UserIdentitiesTable.isPrimary] = command.identity.isPrimary
            }

            listOfNotNull(command.email, command.phone).forEach { contact ->
                ContactInformationTable.insert {
                    it[ContactInformationTable.userId] = userId
                    it[ContactInformationTable.contactType] = contact.type.name
                    it[ContactInformationTable.value] = contact.value
                    it[ContactInformationTable.isPrimary] = contact.isPrimary
                    it[ContactInformationTable.contactSource] = contact.source.name
                    it[ContactInformationTable.verificationStatus] = contact.status.name
                    it[ContactInformationTable.verificationMethod] = contact.method?.name
                }
            }

            command.capabilities.forEach { capability ->
                UserCapabilitiesTable.insert {
                    it[UserCapabilitiesTable.userId] = userId
                    it[UserCapabilitiesTable.capability] = capability.name
                }
            }

            loadAggregate(userId.value) ?: error("Provisioned user ${userId.value} could not be read back")
        }

    fun findById(id: UUID): User? = transaction { loadAggregate(id) }

    fun findByFirebaseUid(firebaseUid: String): User? =
        transaction {
            UsersTable
                .selectAll()
                .where { UsersTable.firebaseUid eq firebaseUid }
                .singleOrNull()
                ?.let { loadAggregate(it[UsersTable.id].value) }
        }

    fun updateProfile(
        id: UUID,
        patch: ProfilePatch,
    ): User? =
        transaction {
            val updated =
                UsersTable.update({ UsersTable.id eq id }) {
                    patch.photoUrl?.let { value -> it[UsersTable.photoUrl] = value }
                    patch.dateOfBirth?.let { value -> it[UsersTable.dateOfBirth] = value }
                    patch.gender?.let { value -> it[UsersTable.gender] = value }
                    patch.city?.let { value -> it[UsersTable.city] = value }
                }
            if (updated == 0) null else loadAggregate(id)
        }

    /** Full replacement of the mutable profile fields (PUT semantics): null clears the column. */
    fun replaceProfile(
        id: UUID,
        patch: ProfilePatch,
    ): User? =
        transaction {
            val updated =
                UsersTable.update({ UsersTable.id eq id }) {
                    it[photoUrl] = patch.photoUrl
                    it[dateOfBirth] = patch.dateOfBirth
                    it[gender] = patch.gender
                    it[city] = patch.city
                }
            if (updated == 0) null else loadAggregate(id)
        }

    /** Soft-delete: flip is_active to false. Returns false if no such user. */
    fun deactivate(id: UUID): Boolean =
        transaction {
            UsersTable.update({ UsersTable.id eq id }) { it[UsersTable.isActive] = false } > 0
        }

    private fun loadAggregate(id: UUID): User? {
        val row =
            UsersTable
                .selectAll()
                .where { UsersTable.id eq id }
                .singleOrNull() ?: return null
        return row.toUser(
            names = namesOf(id),
            contacts = contactsOf(id),
            identities = identitiesOf(id),
            capabilities = capabilitiesOf(id),
        )
    }
}

private fun namesOf(id: UUID): List<UserName> =
    UserNamesTable
        .selectAll()
        .where { UserNamesTable.userId eq id }
        .map {
            UserName(
                type = NameType.valueOf(it[UserNamesTable.nameType]),
                value = it[UserNamesTable.value],
                isPrimary = it[UserNamesTable.isPrimary],
            )
        }

private fun contactsOf(id: UUID): List<Contact> =
    ContactInformationTable
        .selectAll()
        .where { ContactInformationTable.userId eq id }
        .map { it.toContact() }

private fun identitiesOf(id: UUID): List<UserIdentity> =
    UserIdentitiesTable
        .selectAll()
        .where { UserIdentitiesTable.userId eq id }
        .map {
            UserIdentity(
                provider = AuthProvider.valueOf(it[UserIdentitiesTable.provider]),
                providerUid = it[UserIdentitiesTable.providerUid],
                isPrimary = it[UserIdentitiesTable.isPrimary],
            )
        }

private fun capabilitiesOf(id: UUID): Set<Capability> =
    UserCapabilitiesTable
        .selectAll()
        .where { UserCapabilitiesTable.userId eq id }
        .map { Capability.valueOf(it[UserCapabilitiesTable.capability]) }
        .toSet()

private fun ResultRow.toUser(
    names: List<UserName>,
    contacts: List<Contact>,
    identities: List<UserIdentity>,
    capabilities: Set<Capability>,
): User =
    User(
        id = this[UsersTable.id].value,
        firebaseUid = this[UsersTable.firebaseUid],
        photoUrl = this[UsersTable.photoUrl],
        dateOfBirth = this[UsersTable.dateOfBirth],
        gender = this[UsersTable.gender],
        city = this[UsersTable.city],
        country = this[UsersTable.country],
        kycVerified = this[UsersTable.kycVerified],
        isActive = this[UsersTable.isActive],
        names = names,
        contacts = contacts,
        identities = identities,
        capabilities = capabilities,
    )
