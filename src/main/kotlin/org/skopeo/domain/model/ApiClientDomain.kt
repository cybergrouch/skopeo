// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.model

import org.skopeo.common.redaction.Redactable
import org.skopeo.common.security.Capability
import java.time.LocalDateTime
import java.util.UUID

/*
 * The application/client identity layer (#225/#596): a partner application (distinct from an
 * end-user) that calls the API with a hashed static API key. This is the pure-domain shape; hashing,
 * persistence, and HTTP mapping live in the service/repository/route layers.
 */

/** Whether a partner application may authenticate at all. */
enum class ApiClientStatus { ACTIVE, SUSPENDED }

/** The lifecycle of a single API key. */
enum class ApiKeyStatus { ACTIVE, REVOKED }

/** The key's environment, encoded in its plaintext prefix so a key is self-identifying. */
enum class ApiKeyEnvironment(
    val prefix: String,
) {
    LIVE(prefix = "skopeo_live_"),
    TEST(prefix = "skopeo_test_"),
}

/** A partner application and (optionally) its keys. */
data class ApiClient(
    val id: UUID,
    val name: String,
    val status: ApiClientStatus,
    val createdBy: UUID?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val keys: List<ApiKey> = emptyList(),
    // Per-minute rate-limit override (#603); null = the global default tier applies.
    val rateLimitPerMin: Int? = null,
)

/**
 * A single API key. Never carries the plaintext or the hash — [keyPrefix] is the non-secret leading
 * segment shown for identification; [scopes] is the subset of [Capability] the key is authorized for
 * (enforcement is a later phase). Only the hash is persisted.
 */
data class ApiKey(
    val id: UUID,
    val clientId: UUID,
    val keyPrefix: String,
    val scopes: Set<Capability>,
    val status: ApiKeyStatus,
    val createdBy: UUID?,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime?,
    val lastUsedAt: LocalDateTime?,
    val revokedAt: LocalDateTime?,
)

/** All the fields needed to persist a new key row (keeps the repository call under the arg limit). */
data class InsertApiKeyCommand(
    val clientId: UUID,
    val keyPrefix: String,
    val keyHash: String,
    val scopes: Set<Capability>,
    val createdBy: UUID?,
    val expiresAt: LocalDateTime?,
)

/**
 * A freshly-issued key: the persisted [key] plus its one-time [plaintext] secret. The plaintext is
 * returned exactly once at creation and is never stored or recoverable afterwards.
 */
data class IssuedApiKey(
    val client: ApiClient,
    val key: ApiKey,
    /**
     * The plaintext key, shown to the caller exactly once at issuance.
     *
     * [Redactable] because this is a live credential, not merely personal data: only the SHA-256 hash is
     * persisted, so interpolating this object into a log line would put a *working* API key into Cloud
     * Logging, where it would outlive the request by the retention period.
     */
    val plaintext: Redactable<String>,
)

/** A key resolved together with its owning client's status, so the resolver can reject a suspended client. */
data class ResolvedApiKey(
    val key: ApiKey,
    val clientStatus: ApiClientStatus,
)

/** A minimal, public projection of a player for a partner directory read (no PII beyond the public name). */
data class PublicPlayer(
    val publicCode: String,
    val displayName: String?,
)

/** The result of a delegated authorization check (#597): what [clientId] may do for [userId]. */
data class ClientEffectiveCapabilities(
    val clientId: UUID,
    val userId: UUID,
    val capabilities: Set<Capability>,
)
