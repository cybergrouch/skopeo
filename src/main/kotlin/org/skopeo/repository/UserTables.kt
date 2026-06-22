// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

// Column widths mirror the V1 schema's VARCHAR sizes.
private const val FIREBASE_UID_MAX = 128
private const val GENDER_MAX = 10
private const val CITY_MAX = 100
private const val COUNTRY_CODE_LEN = 2
private const val CONTACT_TYPE_MAX = 10
private const val CODE_MAX = 20 // enum-like codes: name_type, provider, source, verification_*, capability
private const val VALUE_MAX = 255 // free-text values: name value, provider_uid, contact value

/**
 * Exposed mappings over the V1 schema. Flyway owns the DDL (these objects never
 * create tables); they declare only the columns the repository reads or writes —
 * DB-managed columns (created_at/updated_at/verified_at/etc.) are intentionally
 * omitted so their defaults and triggers apply untouched.
 */
internal object UsersTable : UUIDTable("users") {
    val firebaseUid = varchar("firebase_uid", FIREBASE_UID_MAX).nullable()
    val photoUrl = text("photo_url").nullable()
    val dateOfBirth = date("date_of_birth").nullable()
    val gender = varchar("gender", GENDER_MAX).nullable()
    val city = varchar("city", CITY_MAX).nullable()
    val country = varchar("country", COUNTRY_CODE_LEN).default("PH")
    val kycVerified = bool("kyc_verified").default(false)
    val isActive = bool("is_active").default(true)
}

internal object UserNamesTable : UUIDTable("user_names") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val nameType = varchar("name_type", CODE_MAX)
    val value = varchar("value", VALUE_MAX)
    val isActive = bool("is_active").default(true)
    val disabledAt = datetime("disabled_at").nullable()
}

internal object UserIdentitiesTable : UUIDTable("user_identities") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val provider = varchar("provider", CODE_MAX)
    val providerUid = varchar("provider_uid", VALUE_MAX)
    val isPrimary = bool("is_primary").default(false)
}

internal object ContactInformationTable : UUIDTable("contact_information") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val contactType = varchar("contact_type", CONTACT_TYPE_MAX)
    val value = varchar("value", VALUE_MAX)
    val isPrimary = bool("is_primary").default(false)
    val contactSource = varchar("source", CODE_MAX)
    val verificationStatus = varchar("verification_status", CODE_MAX).default("PENDING")
    val verificationMethod = varchar("verification_method", CODE_MAX).nullable()
    val verifiedAt = datetime("verified_at").nullable()
    val verifiedBy = reference("verified_by", UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val isActive = bool("is_active").default(true)
    val disabledAt = datetime("disabled_at").nullable()
}

internal object UserCapabilitiesTable : UUIDTable("user_capabilities") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val capability = varchar("capability", CODE_MAX)
    val grantedBy = reference("granted_by", UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val grantedAt = datetime("granted_at").nullable()
    val isActive = bool("is_active").default(true)
    val revokedAt = datetime("revoked_at").nullable()
    val revokedBy = reference("revoked_by", UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
}
