// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.client

import org.skopeo.common.security.ClientPrincipal
import org.skopeo.domain.model.ApiClient
import org.skopeo.domain.model.ApiKey
import org.skopeo.domain.model.ClientEffectiveCapabilities
import org.skopeo.domain.model.IssuedApiKey
import org.skopeo.domain.model.PublicPlayer
import org.skopeo.dto.client.ApiClientResponse
import org.skopeo.dto.client.ApiKeyResponse
import org.skopeo.dto.client.ClientEffectiveCapabilitiesResponse
import org.skopeo.dto.client.ClientIdentityResponse
import org.skopeo.dto.client.IssuedApiKeyResponse
import org.skopeo.dto.client.PartnerPlayerResponse

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
