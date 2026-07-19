// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.datetime

private const val HASH_MAX = 64 // SHA-256 hex
private const val STATUS_MAX = 16

/**
 * Exposed mapping over the V23 placeholder_claim_codes table (#496). Only the SHA-256 hash of a
 * backend-generated claim code is stored — never the plaintext. created_at is DB-managed (default), so
 * it is read-only here.
 */
internal object PlaceholderClaimCodesTable : UUIDTable(name = "placeholder_claim_codes") {
    val placeholderUserId = reference(name = "placeholder_user_id", foreign = UsersTable, onDelete = ReferenceOption.CASCADE)
    val codeHash = varchar(name = "code_hash", length = HASH_MAX)
    val expiresAt = datetime(name = "expires_at")
    val status = varchar(name = "status", length = STATUS_MAX).default(defaultValue = "ACTIVE")
    val createdBy = reference(name = "created_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val createdAt = datetime(name = "created_at")
    val consumedAt = datetime(name = "consumed_at").nullable()
    val consumedBy = reference(name = "consumed_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
}
