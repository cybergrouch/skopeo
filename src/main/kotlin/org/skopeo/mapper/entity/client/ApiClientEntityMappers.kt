// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.entity.client

import org.skopeo.common.security.Capability
import org.skopeo.model.ApiClient
import org.skopeo.model.ApiClientStatus
import org.skopeo.model.ApiKey
import org.skopeo.model.ApiKeyStatus
import org.skopeo.model.ResolvedApiKey
import org.skopeo.persistence.ApiClientAggregateEntity
import org.skopeo.persistence.ApiKeyEntity

// Entity→domain mappers (#633): build the domain ApiClient/ApiKey/ResolvedApiKey from the raw persistence
// entities the repository returns, parsing the as-stored String columns (status enums and the
// comma-separated scopes) here. Lives in `mapper.entity` (which may depend on both `persistence` and
// `model`); the service calls these, since `repository ↛ mapper`.

/** Build the domain [ApiClient] from the raw [ApiClientAggregateEntity] graph, mapping each loaded key. */
fun ApiClientAggregateEntity.toDomain(): ApiClient =
    ApiClient(
        id = client.id,
        name = client.name,
        status = ApiClientStatus.valueOf(value = client.status),
        createdBy = client.createdBy,
        createdAt = client.createdAt,
        updatedAt = client.updatedAt,
        keys = keys.map { it.toDomain() },
        rateLimitPerMin = client.rateLimitPerMin,
    )

/** Build the domain [ApiKey] from the raw key entity, parsing the scopes/status columns. */
fun ApiKeyEntity.toDomain(): ApiKey =
    ApiKey(
        id = id,
        clientId = clientId,
        keyPrefix = keyPrefix,
        scopes = parseScopes(raw = scopes),
        status = ApiKeyStatus.valueOf(value = status),
        createdBy = createdBy,
        createdAt = createdAt,
        expiresAt = expiresAt,
        lastUsedAt = lastUsedAt,
        revokedAt = revokedAt,
    )

/**
 * Build the domain [ResolvedApiKey] from the raw key entity plus its owning client's raw status column
 * ([clientStatusRaw]), so the resolver can reject a suspended client without a second DB read.
 */
fun ApiKeyEntity.toResolved(clientStatusRaw: String): ResolvedApiKey =
    ResolvedApiKey(
        key = toDomain(),
        clientStatus = ApiClientStatus.valueOf(value = clientStatusRaw),
    )

/** Parse the comma-separated scopes column, dropping any value that is no longer a known capability. */
private fun parseScopes(raw: String): Set<Capability> =
    raw
        .split(",")
        .filter { it.isNotBlank() }
        .mapNotNull { name -> Capability.entries.firstOrNull { it.name == name } }
        .toSet()
