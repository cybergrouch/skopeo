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
import org.skopeo.domain.model.ApiClientStatus
import org.skopeo.domain.model.ApiKeyStatus
import org.skopeo.domain.model.InsertApiKeyCommand
import org.skopeo.repository.persistence.ApiClientAggregateEntity
import org.skopeo.repository.persistence.ApiClientEntity
import org.skopeo.repository.persistence.ApiKeyEntity
import java.time.LocalDateTime
import java.util.UUID

/**
 * Persistence for partner API clients and their keys (#225/#596). Only the SHA-256 hash of a key is
 * stored; [findKeyByHash] is the hot path on every client-authenticated request. Returns the raw
 * persistence entities/graphs (#633); the service converts to the domain via `mapper.entity`
 * (the service layer owns the [org.skopeo.common.error.ServiceError] mapping), mirroring [ClubRepository].
 */
@Suppress("TooManyFunctions") // Cohesive CRUD over api_clients/api_keys (clients, keys, resolution, rate limit).
class ApiClientRepository {
    fun createClient(
        name: String,
        createdBy: UUID?,
    ): ApiClientAggregateEntity =
        transaction {
            val id =
                ApiClientsTable.insertAndGetId {
                    it[ApiClientsTable.name] = name
                    it[status] = ApiClientStatus.ACTIVE.name
                    it[ApiClientsTable.createdBy] = createdBy
                }.value
            ApiClientsTable.selectAll().where { ApiClientsTable.id eq id }.single().toApiClientAggregate()
        }

    fun findClientById(id: UUID): ApiClientAggregateEntity? =
        transaction { ApiClientsTable.selectAll().where { ApiClientsTable.id eq id }.singleOrNull()?.toApiClientAggregate() }

    /** Set (or clear, when null) a client's per-minute rate-limit override (#603). Returns the refreshed client, or null if missing. */
    fun setRateLimit(
        clientId: UUID,
        rateLimitPerMin: Int?,
    ): ApiClientAggregateEntity? =
        transaction {
            val updated =
                ApiClientsTable.update(where = { ApiClientsTable.id eq clientId }) {
                    it[ApiClientsTable.rateLimitPerMin] = rateLimitPerMin
                    it[updatedAt] = LocalDateTime.now()
                }
            if (updated > 0) {
                ApiClientsTable.selectAll().where { ApiClientsTable.id eq clientId }.single().toApiClientAggregate()
            } else {
                null
            }
        }

    /** All clients, newest first, each with its keys. */
    fun listClients(): List<ApiClientAggregateEntity> =
        transaction {
            ApiClientsTable.selectAll().orderBy(ApiClientsTable.createdAt to SortOrder.DESC).map { it.toApiClientAggregate() }
        }

    fun insertKey(command: InsertApiKeyCommand): ApiKeyEntity =
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
            ApiKeysTable.selectAll().where { ApiKeysTable.id eq id }.single().toApiKeyEntity()
        }

    /**
     * Resolve a key by its hash, joined to its client's status (for the suspended-client check). Returns
     * null when no key has that hash, else the raw [ApiKeyEntity] paired with the owning client's raw
     * status column (the service builds the domain `ResolvedApiKey` via `mapper.entity`). Does not filter
     * on status/expiry — the service classifies those so it can tell an unknown key (401) from a
     * revoked/expired one (403).
     */
    fun findKeyByHash(hash: String): Pair<ApiKeyEntity, String>? =
        transaction {
            (ApiKeysTable innerJoin ApiClientsTable)
                .selectAll()
                .where { ApiKeysTable.keyHash eq hash }
                .singleOrNull()
                ?.let { row -> row.toApiKeyEntity() to row[ApiClientsTable.status] }
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

    /**
     * Read an `api_clients` row plus its keys into the raw [ApiClientAggregateEntity] graph (#633),
     * loading the keys (runs in the caller's transaction). No domain construction — that is
     * `mapper.entity`'s job.
     */
    private fun ResultRow.toApiClientAggregate(): ApiClientAggregateEntity {
        val entity = toApiClientEntity()
        val keys =
            ApiKeysTable
                .selectAll()
                .where { ApiKeysTable.clientId eq entity.id }
                .orderBy(ApiKeysTable.createdAt to SortOrder.DESC)
                .map { it.toApiKeyEntity() }
        return ApiClientAggregateEntity(client = entity, keys = keys)
    }

    /** Read the raw `api_clients` root-row scalars into the model-free persistence entity (#633). */
    private fun ResultRow.toApiClientEntity(): ApiClientEntity =
        ApiClientEntity(
            id = this[ApiClientsTable.id].value,
            name = this[ApiClientsTable.name],
            status = this[ApiClientsTable.status],
            rateLimitPerMin = this[ApiClientsTable.rateLimitPerMin],
            createdBy = this[ApiClientsTable.createdBy]?.value,
            createdAt = this[ApiClientsTable.createdAt],
            updatedAt = this[ApiClientsTable.updatedAt],
        )

    /** Read the raw `api_keys` row scalars into the model-free persistence entity (#633). */
    private fun ResultRow.toApiKeyEntity(): ApiKeyEntity =
        ApiKeyEntity(
            id = this[ApiKeysTable.id].value,
            clientId = this[ApiKeysTable.clientId].value,
            keyPrefix = this[ApiKeysTable.keyPrefix],
            keyHash = this[ApiKeysTable.keyHash],
            scopes = this[ApiKeysTable.scopes],
            status = this[ApiKeysTable.status],
            createdBy = this[ApiKeysTable.createdBy]?.value,
            createdAt = this[ApiKeysTable.createdAt],
            expiresAt = this[ApiKeysTable.expiresAt],
            lastUsedAt = this[ApiKeysTable.lastUsedAt],
            revokedAt = this[ApiKeysTable.revokedAt],
        )
}
