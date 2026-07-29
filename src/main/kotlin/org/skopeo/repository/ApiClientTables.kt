// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.datetime

private const val CLIENT_NAME_MAX = 120
private const val STATUS_MAX = 20
private const val KEY_PREFIX_MAX = 32
private const val KEY_HASH_MAX = 64
private const val SCOPES_MAX = 600

/**
 * Exposed mappings over the V33 api_clients / api_keys tables (#225/#596). created_at/updated_at are
 * DB-managed (defaults), so they are not set on insert. Only the SHA-256 [ApiKeysTable.keyHash] is
 * stored — never the plaintext.
 */
internal object ApiClientsTable : UUIDTable(name = "api_clients") {
    val name = varchar(name = "name", length = CLIENT_NAME_MAX)
    val status = varchar(name = "status", length = STATUS_MAX)
    val createdBy = reference(name = "created_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val createdAt = datetime(name = "created_at")
    val updatedAt = datetime(name = "updated_at")
}

internal object ApiKeysTable : UUIDTable(name = "api_keys") {
    val clientId = reference(name = "client_id", foreign = ApiClientsTable, onDelete = ReferenceOption.CASCADE)
    val keyPrefix = varchar(name = "key_prefix", length = KEY_PREFIX_MAX)
    val keyHash = varchar(name = "key_hash", length = KEY_HASH_MAX)
    val scopes = varchar(name = "scopes", length = SCOPES_MAX)
    val status = varchar(name = "status", length = STATUS_MAX)
    val createdBy = reference(name = "created_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val createdAt = datetime(name = "created_at")
    val expiresAt = datetime(name = "expires_at").nullable()
    val lastUsedAt = datetime(name = "last_used_at").nullable()
    val revokedAt = datetime(name = "revoked_at").nullable()
}
