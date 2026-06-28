// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

// Column widths mirror the V1 schema's VARCHAR sizes.
private const val FIREBASE_UID_MAX = 128
private const val SEX_MAX = 10
private const val CITY_MAX = 100
private const val COUNTRY_CODE_LEN = 2
private const val CONTACT_TYPE_MAX = 10
private const val CODE_MAX = 20 // enum-like codes: name_type, provider, source, verification_*, capability
private const val VALUE_MAX = 255 // free-text values: name value, provider_uid, contact value
private const val PUBLIC_CODE_LEN = 6 // shareable player code
private const val RATING_PRECISION = 10 // NUMERIC(10,6), mirrors user_ratings.current_rating
private const val RATING_SCALE = 6

/**
 * Exposed mappings over the V1 schema. Flyway owns the DDL (these objects never
 * create tables); they declare only the columns the repository reads or writes —
 * DB-managed columns (created_at/updated_at/verified_at/etc.) are intentionally
 * omitted so their defaults and triggers apply untouched.
 */
internal object UsersTable : UUIDTable(name = "users") {
    val firebaseUid = varchar(name = "firebase_uid", length = FIREBASE_UID_MAX).nullable()
    val photoUrl = text(name = "photo_url").nullable()
    val dateOfBirth = date(name = "date_of_birth").nullable()
    val sex = varchar(name = "sex", length = SEX_MAX).nullable()
    val city = varchar(name = "city", length = CITY_MAX).nullable()
    val country = varchar(name = "country", length = COUNTRY_CODE_LEN).default(defaultValue = "PH")
    val kycVerified = bool(name = "kyc_verified").default(defaultValue = false)
    val isActive = bool(name = "is_active").default(defaultValue = true)
    val publicCode = varchar(name = "public_code", length = PUBLIC_CODE_LEN)
    val proposedRating = decimal(name = "proposed_rating", precision = RATING_PRECISION, scale = RATING_SCALE).nullable()

    // When set, this user is a disabled duplicate of the referenced canonical account (#124).
    val canonicalUserId =
        reference(name = "canonical_user_id", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
}

internal object UserNamesTable : UUIDTable(name = "user_names") {
    val userId = reference(name = "user_id", foreign = UsersTable, onDelete = ReferenceOption.CASCADE)
    val nameType = varchar(name = "name_type", length = CODE_MAX)
    val value = varchar(name = "value", length = VALUE_MAX)
    val isActive = bool(name = "is_active").default(defaultValue = true)
    val disabledAt = datetime(name = "disabled_at").nullable()
}

internal object UserIdentitiesTable : UUIDTable(name = "user_identities") {
    val userId = reference(name = "user_id", foreign = UsersTable, onDelete = ReferenceOption.CASCADE)
    val provider = varchar(name = "provider", length = CODE_MAX)
    val providerUid = varchar(name = "provider_uid", length = VALUE_MAX)
    val isPrimary = bool(name = "is_primary").default(defaultValue = false)
}

internal object ContactInformationTable : UUIDTable(name = "contact_information") {
    val userId = reference(name = "user_id", foreign = UsersTable, onDelete = ReferenceOption.CASCADE)
    val contactType = varchar(name = "contact_type", length = CONTACT_TYPE_MAX)
    val value = varchar(name = "value", length = VALUE_MAX)
    val isPrimary = bool(name = "is_primary").default(defaultValue = false)
    val contactSource = varchar(name = "source", length = CODE_MAX)
    val verificationStatus = varchar(name = "verification_status", length = CODE_MAX).default(defaultValue = "PENDING")
    val verificationMethod = varchar(name = "verification_method", length = CODE_MAX).nullable()
    val verifiedAt = datetime(name = "verified_at").nullable()
    val verifiedBy = reference(name = "verified_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val isActive = bool(name = "is_active").default(defaultValue = true)
    val disabledAt = datetime(name = "disabled_at").nullable()
}

internal object UserCapabilitiesTable : UUIDTable(name = "user_capabilities") {
    val userId = reference(name = "user_id", foreign = UsersTable, onDelete = ReferenceOption.CASCADE)
    val capability = varchar(name = "capability", length = CODE_MAX)
    val grantedBy = reference(name = "granted_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val grantedAt = datetime(name = "granted_at").nullable()
    val isActive = bool(name = "is_active").default(defaultValue = true)
    val revokedAt = datetime(name = "revoked_at").nullable()
    val revokedBy = reference(name = "revoked_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
}
