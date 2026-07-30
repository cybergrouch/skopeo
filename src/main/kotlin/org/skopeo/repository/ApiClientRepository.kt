// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.common.security.Capability
import org.skopeo.model.ApiClient
import org.skopeo.model.ApiClientStatus
import org.skopeo.model.ApiKey
import org.skopeo.model.ApiKeyStatus
import org.skopeo.model.InsertApiKeyCommand
import org.skopeo.model.ResolvedApiKey
import java.time.LocalDateTime
import java.util.UUID

/**
 * Persistence for partner API clients and their keys (#225/#596). Only the SHA-256 hash of a key is
 * stored; [findKeyByHash] is the hot path on every client-authenticated request. Returns raw domain
 * (the service layer owns the [org.skopeo.common.error.ServiceError] mapping), mirroring [ClubRepository].
 */
@Suppress("TooManyFunctions") // Cohesive CRUD over api_clients/api_keys (clients, keys, resolution, rate limit).
class ApiClientRepository {
    fun createClient(
        name: String,
        createdBy: UUID?,
    ): ApiClient =
        transaction {
            val id =
                ApiClientsTable.insertAndGetId {
                    it[ApiClientsTable.name] = name
                    it[status] = ApiClientStatus.ACTIVE.name
                    it[ApiClientsTable.createdBy] = createdBy
                }.value
            ApiClientsTable.selectAll().where { ApiClientsTable.id eq id }.single().toClient()
        }

    fun findClientById(id: UUID): ApiClient? =
        transaction { ApiClientsTable.selectAll().where { ApiClientsTable.id eq id }.singleOrNull()?.toClient() }

    /** Set (or clear, when null) a client's per-minute rate-limit override (#603). Returns the refreshed client, or null if missing. */
    fun setRateLimit(
        clientId: UUID,
        rateLimitPerMin: Int?,
    ): ApiClient? =
        transaction {
            val updated =
                ApiClientsTable.update(where = { ApiClientsTable.id eq clientId }) {
                    it[ApiClientsTable.rateLimitPerMin] = rateLimitPerMin
                    it[updatedAt] = LocalDateTime.now()
                }
            if (updated > 0) {
                ApiClientsTable.selectAll().where { ApiClientsTable.id eq clientId }.single().toClient()
            } else {
                null
            }
        }

    /** All clients, newest first, each with its keys. */
    fun listClients(): List<ApiClient> =
        transaction {
            ApiClientsTable.selectAll().orderBy(ApiClientsTable.createdAt to SortOrder.DESC).map { it.toClient() }
        }

    fun insertKey(command: InsertApiKeyCommand): ApiKey =
        transaction {
            val id =
                ApiKeysTable.insertAndGetId {
                    it[clientId] = command.clientId
                    it[keyPrefix] = command.keyPrefix
                    it[keyHash] = command.keyHash
                    it[scopes] = command.scopes.joinToString(separator = ",") { scope -> scope.name }
                    it[status] = ApiKeyStatus.ACTIVE.name
                    it[createdBy] = command.createdBy
                    it[expiresAt] = command.expiresAt
                }.value
            ApiKeysTable.selectAll().where { ApiKeysTable.id eq id }.single().toKey()
        }

    /**
     * Resolve a key by its hash, joined to its client's status (for the suspended-client check). Returns
     * null when no key has that hash. Does not filter on status/expiry — the service classifies those so
     * it can tell an unknown key (401) from a revoked/expired one (403).
     */
    fun findKeyByHash(hash: String): ResolvedApiKey? =
        transaction {
            (ApiKeysTable innerJoin ApiClientsTable)
                .selectAll()
                .where { ApiKeysTable.keyHash eq hash }
                .singleOrNull()
                ?.let { row ->
                    ResolvedApiKey(
                        key = row.toKey(),
                        clientStatus = ApiClientStatus.valueOf(value = row[ApiClientsTable.status]),
                    )
                }
        }

    /** Record that a key was just used (best-effort observability; not on the auth critical path). */
    fun touchLastUsed(
        keyId: UUID,
        usedAt: LocalDateTime,
    ): Unit =
        transaction {
            ApiKeysTable.update(where = { ApiKeysTable.id eq keyId }) { it[lastUsedAt] = usedAt }
            Unit
        }

    /**
     * Revoke an active key belonging to [clientId]. Returns true if an active key was revoked (false if
     * missing, already revoked, or owned by a different client).
     */
    fun revokeKey(
        clientId: UUID,
        keyId: UUID,
        revokedAt: LocalDateTime,
    ): Boolean =
        transaction {
            ApiKeysTable.update(
                where = {
                    (ApiKeysTable.id eq keyId) and
                        (ApiKeysTable.clientId eq clientId) and
                        (ApiKeysTable.status eq ApiKeyStatus.ACTIVE.name)
                },
            ) {
                it[status] = ApiKeyStatus.REVOKED.name
                it[ApiKeysTable.revokedAt] = revokedAt
            } > 0
        }

    /** Map a clients row to the domain, loading its keys (runs in the caller's transaction). */
    private fun ResultRow.toClient(): ApiClient {
        val clientId = this[ApiClientsTable.id].value
        val keys =
            ApiKeysTable
                .selectAll()
                .where { ApiKeysTable.clientId eq clientId }
                .orderBy(ApiKeysTable.createdAt to SortOrder.DESC)
                .map { it.toKey() }
        return ApiClient(
            id = clientId,
            name = this[ApiClientsTable.name],
            status = ApiClientStatus.valueOf(value = this[ApiClientsTable.status]),
            createdBy = this[ApiClientsTable.createdBy]?.value,
            createdAt = this[ApiClientsTable.createdAt],
            updatedAt = this[ApiClientsTable.updatedAt],
            keys = keys,
            rateLimitPerMin = this[ApiClientsTable.rateLimitPerMin],
        )
    }

    private fun ResultRow.toKey(): ApiKey =
        ApiKey(
            id = this[ApiKeysTable.id].value,
            clientId = this[ApiKeysTable.clientId].value,
            keyPrefix = this[ApiKeysTable.keyPrefix],
            scopes = parseScopes(raw = this[ApiKeysTable.scopes]),
            status = ApiKeyStatus.valueOf(value = this[ApiKeysTable.status]),
            createdBy = this[ApiKeysTable.createdBy]?.value,
            createdAt = this[ApiKeysTable.createdAt],
            expiresAt = this[ApiKeysTable.expiresAt],
            lastUsedAt = this[ApiKeysTable.lastUsedAt],
            revokedAt = this[ApiKeysTable.revokedAt],
        )

    /** Parse the comma-separated scopes column, dropping any value that is no longer a known capability. */
    private fun parseScopes(raw: String): Set<Capability> =
        raw
            .split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { name -> Capability.entries.firstOrNull { it.name == name } }
            .toSet()
}
