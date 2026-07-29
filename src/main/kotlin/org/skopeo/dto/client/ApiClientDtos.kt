// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.client

import kotlinx.serialization.Serializable
import org.skopeo.model.ApiClient
import org.skopeo.model.ApiKey
import org.skopeo.model.ClientEffectiveCapabilities
import org.skopeo.model.ClientPrincipal
import org.skopeo.model.IssuedApiKey
import org.skopeo.model.PublicPlayer

/** Body for `POST /api/v1/api-clients` — an administrator registers a partner application. */
@Serializable
data class CreateApiClientRequest(
    val name: String,
)

/**
 * Body for `POST /api/v1/api-clients/{id}/keys` — issue a key. [scopes] are Capability names the key is
 * authorized for (least privilege; enforced in a later phase). [environment] is `LIVE` (default) or
 * `TEST`; [expiresInDays], when set, bounds the key's lifetime.
 */
@Serializable
data class IssueApiKeyRequest(
    val scopes: List<String> = emptyList(),
    val environment: String? = null,
    val expiresInDays: Long? = null,
)

/** A key's non-secret metadata — never the plaintext or the hash. */
@Serializable
data class ApiKeyResponse(
    val id: String,
    val keyPrefix: String,
    val scopes: List<String>,
    val status: String,
    val createdAt: String,
    val expiresAt: String? = null,
    val lastUsedAt: String? = null,
    val revokedAt: String? = null,
)

@Serializable
data class ApiClientResponse(
    val id: String,
    val name: String,
    val status: String,
    val createdAt: String,
    val keys: List<ApiKeyResponse>,
    // Per-minute rate-limit override (#603); null = the global default tier applies.
    val rateLimitPerMin: Int? = null,
)

/** Body for `PUT /api/v1/api-clients/{id}/rate-limit` (#603) — null clears the override (use default). */
@Serializable
data class SetRateLimitRequest(
    val rateLimitPerMin: Int? = null,
)

/**
 * Response for `POST /api/v1/api-clients/{id}/keys`. [apiKey] is the plaintext secret, shown **exactly
 * once** — it is never stored and cannot be retrieved again.
 */
@Serializable
data class IssuedApiKeyResponse(
    val apiKey: String,
    val key: ApiKeyResponse,
)

/** Response for `GET /api/v1/client/me` — the resolved identity behind a valid API key. */
@Serializable
data class ClientIdentityResponse(
    val clientId: String,
    val scopes: List<String>,
)

/** A player in the partner directory read (`GET /api/v1/client/players`, #597) — public fields only. */
@Serializable
data class PartnerPlayerResponse(
    val publicCode: String,
    val displayName: String? = null,
)

/**
 * Response for `GET /api/v1/client/me/capabilities` (#597): what the calling app may do on behalf of the
 * signed-in user — the intersection of the key's scopes and the user's capabilities.
 */
@Serializable
data class ClientEffectiveCapabilitiesResponse(
    val clientId: String,
    val userId: String,
    val capabilities: List<String>,
)

fun ApiKey.toResponse(): ApiKeyResponse =
    ApiKeyResponse(
        id = id.toString(),
        keyPrefix = keyPrefix,
        scopes = scopes.map { it.name },
        status = status.name,
        createdAt = createdAt.toString(),
        expiresAt = expiresAt?.toString(),
        lastUsedAt = lastUsedAt?.toString(),
        revokedAt = revokedAt?.toString(),
    )

fun ApiClient.toResponse(): ApiClientResponse =
    ApiClientResponse(
        id = id.toString(),
        name = name,
        status = status.name,
        createdAt = createdAt.toString(),
        keys = keys.map { it.toResponse() },
        rateLimitPerMin = rateLimitPerMin,
    )

fun IssuedApiKey.toResponse(): IssuedApiKeyResponse =
    IssuedApiKeyResponse(
        apiKey = plaintext,
        key = key.toResponse(),
    )

fun ClientPrincipal.toResponse(): ClientIdentityResponse =
    ClientIdentityResponse(
        clientId = clientId.toString(),
        scopes = scopes.map { it.name },
    )

fun PublicPlayer.toResponse(): PartnerPlayerResponse =
    PartnerPlayerResponse(
        publicCode = publicCode,
        displayName = displayName,
    )

fun ClientEffectiveCapabilities.toResponse(): ClientEffectiveCapabilitiesResponse =
    ClientEffectiveCapabilitiesResponse(
        clientId = clientId.toString(),
        userId = userId.toString(),
        capabilities = capabilities.map { it.name },
    )
