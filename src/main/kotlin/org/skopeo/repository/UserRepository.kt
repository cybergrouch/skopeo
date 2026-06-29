// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.jetbrains.exposed.sql.CustomFunction
import org.jetbrains.exposed.sql.FloatColumnType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.stringParam
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.Contact
import org.skopeo.model.Name
import org.skopeo.model.NumericRange
import org.skopeo.model.ProfilePatch
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserSearchQuery
import java.text.Normalizer
import java.util.UUID

private const val SEARCH_LIMIT = 20

// pg_trgm similarity floor for fuzzy name matches (0..1); the substring match below it is
// still included via OR. 0.3 is pg_trgm's own default — typo-tolerant without much noise.
private const val SIMILARITY_THRESHOLD = 0.3f

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
                    it[UsersTable.publicCode] = generateUniquePublicCode()
                    it[UsersTable.firebaseUid] = command.firebaseUid
                    it[UsersTable.photoUrl] = command.photoUrl
                    it[UsersTable.dateOfBirth] = command.dateOfBirth
                    it[UsersTable.sex] = command.sex
                    it[UsersTable.city] = command.city
                    it[UsersTable.country] = command.country ?: "PH"
                    it[UsersTable.proposedRating] = command.proposedRating
                }

            command.names.forEach { name ->
                UserNamesTable.insert {
                    it[UserNamesTable.userId] = userId
                    it[UserNamesTable.nameType] = name.type.name
                    it[UserNamesTable.value] = name.value
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

            loadAggregateOrThrow(id = userId.value)
        }

    fun findById(id: UUID): Either<ServiceError, User> = transaction { aggregateOrNotFound(id = id) }

    /** Resolve multiple ids to their aggregates in one transaction; unknown ids are dropped. */
    fun findAllByIds(ids: List<UUID>): List<User> = transaction { ids.distinct().mapNotNull { loadAggregate(id = it) } }

    /**
     * Active users matching every supplied facet of [query] (AND): a fuzzy name match (trigram
     * similarity, so "Alyce" finds "Alice", plus substring) across any of a profile's names; an
     * exact [UserSearchQuery.sex]; a date-of-birth window (from an age range); a prefix match on the
     * shareable player [UserSearchQuery.code] (so partial codes surface incrementally, issue #86);
     * and an NTRP rating range. Querying the users table keeps results one-per-profile; capped at
     * [SEARCH_LIMIT].
     */
    fun search(query: UserSearchQuery): List<User> =
        transaction {
            val conditions =
                buildList {
                    add(element = Op.build { UsersTable.isActive eq true })
                    query.sex?.let { sex -> add(element = Op.build { UsersTable.sex eq sex }) }
                    query.dobMin?.let { min -> add(element = Op.build { UsersTable.dateOfBirth greaterEq min }) }
                    query.dobMax?.let { max -> add(element = Op.build { UsersTable.dateOfBirth lessEq max }) }
                    query.name?.let { name -> add(element = nameMatches(name = name)) }
                    // Prefix match (#86): the service uppercases the term and codes are stored
                    // uppercase, so a plain LIKE 'PREFIX%' matches partial codes case-insensitively.
                    query.code?.let { code -> add(element = Op.build { UsersTable.publicCode like "$code%" }) }
                    query.q?.let { term -> add(element = nameOrCodeMatches(term = term)) }
                    query.rating?.let { range -> add(element = ratingMatches(range = range)) }
                }
            UsersTable
                .selectAll()
                .where { conditions.reduce { acc, op -> acc and op } }
                .orderBy(UsersTable.id to SortOrder.ASC)
                .limit(n = SEARCH_LIMIT)
                .map { loadAggregate(id = it[UsersTable.id].value)!! }
        }

    fun findByFirebaseUid(firebaseUid: String): User? =
        transaction {
            UsersTable
                .selectAll()
                .where { UsersTable.firebaseUid eq firebaseUid }
                .singleOrNull()
                ?.let { loadAggregate(id = it[UsersTable.id].value) }
        }

    /** Resolve a user by their shareable public code (exact match; caller normalizes case). */
    fun findByPublicCode(code: String): User? =
        transaction {
            UsersTable
                .selectAll()
                .where { UsersTable.publicCode eq code }
                .singleOrNull()
                ?.let { loadAggregate(id = it[UsersTable.id].value) }
        }

    fun updateProfile(
        id: UUID,
        patch: ProfilePatch,
    ): Either<ServiceError, User> =
        transaction {
            val updated =
                UsersTable.update(where = { UsersTable.id eq id }) {
                    patch.photoUrl?.let { value -> it[UsersTable.photoUrl] = value }
                    patch.dateOfBirth?.let { value -> it[UsersTable.dateOfBirth] = value }
                    patch.sex?.let { value -> it[UsersTable.sex] = value }
                    patch.city?.let { value -> it[UsersTable.city] = value }
                }
            if (updated == 0) ServiceError.NotFound(message = "User $id not found").left() else aggregateOrNotFound(id = id)
        }

    /** Full replacement of the mutable profile fields (PUT semantics): null clears the column. */
    fun replaceProfile(
        id: UUID,
        patch: ProfilePatch,
    ): Either<ServiceError, User> =
        transaction {
            val updated =
                UsersTable.update(where = { UsersTable.id eq id }) {
                    it[photoUrl] = patch.photoUrl
                    it[dateOfBirth] = patch.dateOfBirth
                    it[sex] = patch.sex
                    it[city] = patch.city
                }
            if (updated == 0) ServiceError.NotFound(message = "User $id not found").left() else aggregateOrNotFound(id = id)
        }

    /** Soft-delete: flip is_active to false — a [ServiceError.NotFound] when there is no such user. */
    fun deactivate(id: UUID): Either<ServiceError, Unit> =
        transaction {
            val updated = UsersTable.update(where = { UsersTable.id eq id }) { it[UsersTable.isActive] = false }
            if (updated == 0) ServiceError.NotFound(message = "User $id not found").left() else Unit.right()
        }

    /**
     * Mark each user as a disabled duplicate of [canonicalId] (#124): set the canonical pointer and
     * deactivate, in one transaction. Reversible via [restoreDuplicate].
     */
    fun markDuplicates(
        canonicalId: UUID,
        duplicateIds: List<UUID>,
    ): Unit =
        transaction {
            duplicateIds.forEach { duplicateId ->
                UsersTable.update(where = { UsersTable.id eq duplicateId }) {
                    it[canonicalUserId] = canonicalId
                    it[isActive] = false
                }
            }
        }

    /** Reverse a duplicate marking: reactivate and clear the canonical pointer (#124). */
    fun restoreDuplicate(id: UUID): Unit =
        transaction {
            UsersTable.update(where = { UsersTable.id eq id }) {
                it[canonicalUserId] = null
                it[isActive] = true
            }
        }

    /** The disabled duplicates pointing at [canonicalId] (#124) — for the history merge and admin list. */
    fun findDuplicatesOf(canonicalId: UUID): List<User> =
        transaction {
            UsersTable
                .selectAll()
                .where { UsersTable.canonicalUserId eq canonicalId }
                .map { it[UsersTable.id].value }
                .mapNotNull { loadAggregate(id = it) }
        }

    /** Load the aggregate as an [Either], turning absence into a [ServiceError.NotFound]. Must run in a transaction. */
    private fun aggregateOrNotFound(id: UUID): Either<ServiceError, User> {
        val user = loadAggregate(id = id)
        return if (user == null) ServiceError.NotFound(message = "User $id not found").left() else user.right()
    }

    private fun loadAggregate(id: UUID): User? =
        UsersTable.selectAll().where { UsersTable.id eq id }.singleOrNull()?.let { row -> buildAggregate(id = id, row = row) }

    private fun loadAggregateOrThrow(id: UUID): User =
        buildAggregate(id = id, row = UsersTable.selectAll().where { UsersTable.id eq id }.single())
}

private fun buildAggregate(
    id: UUID,
    row: ResultRow,
): User =
    row.toUser(
        names = namesOf(id = id),
        contacts = contactsOf(id = id),
        identities = identitiesOf(id = id),
        capabilities = capabilitiesOf(id = id),
    )

/**
 * The unified picker term (#86): a fuzzy name match OR a player-code prefix, OR-combined so typing
 * either a name or a partial code surfaces players incrementally. Codes are stored uppercase, so the
 * prefix is uppercased; the name part normalizes case itself.
 */
private fun nameOrCodeMatches(term: String): Op<Boolean> =
    nameMatches(name = term) or Op.build { UsersTable.publicCode like "${term.uppercase()}%" }

/** Unicode combining marks left behind by NFD decomposition (the diacritics to strip when folding). */
private val COMBINING_MARKS = Regex(pattern = "\\p{Mn}+")

/**
 * Correlated EXISTS: the user has an active name fuzzily matching [name]. Both sides are accent-folded
 * (`unaccent(lower(value))` in SQL; NFD-decompose-and-strip for the term) so "maria" finds "María
 * García" and "jose" finds "José" — common in Philippine names (accent-insensitive search). The folded
 * substring match is the primary path; pg_trgm SIMILARITY on the folded forms adds typo tolerance.
 */
private fun nameMatches(name: String): Op<Boolean> {
    val normalized =
        Normalizer.normalize(name, Normalizer.Form.NFD).replace(regex = COMBINING_MARKS, replacement = "").lowercase()
    val foldedName =
        CustomFunction(
            functionName = "unaccent",
            columnType = TextColumnType(),
            UserNamesTable.value.lowerCase(),
        )
    val proximity =
        CustomFunction(
            functionName = "SIMILARITY",
            columnType = FloatColumnType(),
            foldedName,
            stringParam(value = normalized),
        )
    return exists(
        query =
            UserNamesTable.selectAll().where {
                (UserNamesTable.userId eq UsersTable.id) and UserNamesTable.isActive and
                    ((foldedName like "%$normalized%") or (proximity greaterEq SIMILARITY_THRESHOLD))
            },
    )
}

/** Correlated EXISTS: the user has a rating within [range] (inclusive/exclusive per bound). */
private fun ratingMatches(range: NumericRange): Op<Boolean> =
    exists(
        query =
            UserRatingsTable.selectAll().where {
                var op: Op<Boolean> = UserRatingsTable.userId eq UsersTable.id
                range.lower?.let { bound ->
                    op = op and
                        if (bound.inclusive) {
                            UserRatingsTable.currentRating greaterEq bound.value
                        } else {
                            UserRatingsTable.currentRating greater bound.value
                        }
                }
                range.upper?.let { bound ->
                    op = op and
                        if (bound.inclusive) {
                            UserRatingsTable.currentRating lessEq bound.value
                        } else {
                            UserRatingsTable.currentRating less bound.value
                        }
                }
                op
            },
    )

private fun namesOf(id: UUID): List<Name> =
    UserNamesTable
        .selectAll()
        .where { UserNamesTable.userId eq id }
        .map { it.toName() }

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
                provider = AuthProvider.valueOf(value = it[UserIdentitiesTable.provider]),
                providerUid = it[UserIdentitiesTable.providerUid],
                isPrimary = it[UserIdentitiesTable.isPrimary],
            )
        }

private fun capabilitiesOf(id: UUID): Set<Capability> =
    UserCapabilitiesTable
        .selectAll()
        .where { (UserCapabilitiesTable.userId eq id) and UserCapabilitiesTable.isActive }
        .map { Capability.valueOf(value = it[UserCapabilitiesTable.capability]) }
        .toSet()

private fun ResultRow.toUser(
    names: List<Name>,
    contacts: List<Contact>,
    identities: List<UserIdentity>,
    capabilities: Set<Capability>,
): User =
    User(
        id = this[UsersTable.id].value,
        publicCode = this[UsersTable.publicCode],
        firebaseUid = this[UsersTable.firebaseUid],
        photoUrl = this[UsersTable.photoUrl],
        dateOfBirth = this[UsersTable.dateOfBirth],
        sex = this[UsersTable.sex],
        city = this[UsersTable.city],
        country = this[UsersTable.country],
        kycVerified = this[UsersTable.kycVerified],
        isActive = this[UsersTable.isActive],
        proposedRating = this[UsersTable.proposedRating],
        canonicalUserId = this[UsersTable.canonicalUserId]?.value,
        names = names,
        contacts = contacts,
        identities = identities,
        capabilities = capabilities,
    )

/** Generate a unique shareable player code (issue #56), retrying on the (rare) collision. Must run in a transaction. */
private fun generateUniquePublicCode(): String =
    PublicCode.generate { code -> UsersTable.selectAll().where { UsersTable.publicCode eq code }.any() }
