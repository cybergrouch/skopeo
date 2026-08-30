// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.client

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.skopeo.common.dto.client.ApiClientResponse
import org.skopeo.common.dto.client.ClientEffectiveCapabilitiesResponse
import org.skopeo.common.dto.client.ClientIdentityResponse
import org.skopeo.common.dto.client.IssuedApiKeyResponse
import org.skopeo.common.dto.client.PartnerPlayerResponse
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.common.security.ClientAuthResult
import org.skopeo.common.security.ClientPrincipal
import org.skopeo.common.security.effectiveCapabilities
import org.skopeo.common.security.hasScope
import org.skopeo.domain.mapper.dto.client.toResponse
import org.skopeo.domain.mapper.entity.client.toDomain
import org.skopeo.domain.mapper.entity.client.toResolved
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.ApiClientStatus
import org.skopeo.domain.model.ApiKeyEnvironment
import org.skopeo.domain.model.ApiKeyStatus
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuditEntityType
import org.skopeo.domain.model.AuditWrite
import org.skopeo.domain.model.ClientEffectiveCapabilities
import org.skopeo.domain.model.InsertApiKeyCommand
import org.skopeo.domain.model.IssuedApiKey
import org.skopeo.domain.model.PublicPlayer
import org.skopeo.domain.model.UserSearchQuery
import org.skopeo.domain.service.audit.AuditService
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.domain.service.user.displayName
import org.skopeo.repository.ApiClientRepository
import org.skopeo.repository.UserRepository
import java.time.LocalDateTime
import java.util.UUID

private const val CLIENT_NAME_MAX = 120

/**
 * Manage partner API clients and their keys (#225/#596), and resolve an incoming key to a
 * [ClientPrincipal]. Management is ADMINISTRATOR-only; [authenticate] is the un-gated resolver used by
 * the client-auth route helper. Only the SHA-256 hash of a key is stored — the plaintext is returned
 * once from [issueKey] and never persisted or logged.
 *
 * Expected failures are returned as an [Either] left ([ServiceError], #115) rather than thrown.
 */
@Suppress("TooManyFunctions") // Cohesive: admin CRUD (create/list/issue/revoke) + the key resolver + scope/delegation reads.
class ApiClientService(
    private val clients: ApiClientRepository = ApiClientRepository(),
    private val users: UserRepository = UserRepository(),
    private val audit: AuditService = AuditService(),
) {
    fun createClient(
        token: VerifiedFirebaseToken,
        name: String,
    ): Either<ServiceError, ApiClientResponse> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val trimmed = name.trim()
            ensure(condition = trimmed.isNotBlank()) { ServiceError.Validation(message = "Client name is required") }
            ensure(condition = trimmed.length <= CLIENT_NAME_MAX) {
                ServiceError.Validation(message = "Client name must be at most $CLIENT_NAME_MAX characters")
            }
            val client = clients.createClient(name = trimmed, createdBy = adminId).toDomain()
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.API_CLIENT_CREATED,
                        entityType = AuditEntityType.API_CLIENT,
                        entityId = client.id,
                        summary = "Created API client ${client.name}",
                        details = mapOf("clientId" to client.id.toString(), "name" to client.name),
                    ),
            )
            client.toResponse()
        }

    fun listClients(token: VerifiedFirebaseToken): Either<ServiceError, List<ApiClientResponse>> =
        either {
            requireAdmin(token = token).bind()
            clients.listClients().map { it.toDomain().toResponse() }
        }

    /**
     * Issue a new key for [clientId]. Returns the [IssuedApiKey] whose plaintext must be shown to the
     * admin exactly once. [expiresInDays], when set, bounds the key's lifetime.
     */
    fun issueKey(
        token: VerifiedFirebaseToken,
        clientId: UUID,
        scopeNames: Set<String>,
        environmentRaw: String?,
        expiresInDays: Long?,
    ): Either<ServiceError, IssuedApiKeyResponse> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val scopes = scopeNames.map { parseScope(raw = it).bind() }.toSet()
            val environment = environmentRaw?.let { parseEnvironment(raw = it).bind() } ?: ApiKeyEnvironment.LIVE
            ensure(condition = expiresInDays == null || expiresInDays > 0) {
                ServiceError.Validation(message = "expiresInDays must be positive")
            }
            val client =
                ensureNotNull(value = clients.findClientById(id = clientId)) {
                    ServiceError.NotFound(message = "API client $clientId not found")
                }.toDomain()
            val generated = ApiKeyCrypto.generate(environment = environment)
            val key =
                clients.insertKey(
                    command =
                        InsertApiKeyCommand(
                            clientId = clientId,
                            keyPrefix = generated.displayPrefix,
                            keyHash = generated.hash,
                            scopes = scopes,
                            createdBy = adminId,
                            expiresAt = expiresInDays?.let { LocalDateTime.now().plusDays(it) },
                        ),
                ).toDomain()
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.API_KEY_ISSUED,
                        entityType = AuditEntityType.API_KEY,
                        entityId = key.id,
                        summary = "Issued API key ${key.keyPrefix}… for ${client.name}",
                        details =
                            mapOf(
                                "clientId" to clientId.toString(),
                                "keyId" to key.id.toString(),
                                "scopes" to scopes.joinToString(separator = ",") { it.name },
                            ),
                    ),
            )
            // generated.plaintext is already Redactable (#822), so it passes straight through;
            // `toResponse()` unwraps it, so the caller still receives the real key exactly once.
            IssuedApiKey(client = client, key = key, plaintext = generated.plaintext).toResponse()
        }

    /** Revoke a key. ADMINISTRATOR-only; a missing/already-revoked key is a [ServiceError.NotFound]. */
    fun revokeKey(
        token: VerifiedFirebaseToken,
        clientId: UUID,
        keyId: UUID,
    ): Either<ServiceError, Unit> =
        either {
            val adminId = requireAdmin(token = token).bind()
            ensure(condition = clients.revokeKey(clientId = clientId, keyId = keyId, revokedAt = LocalDateTime.now())) {
                ServiceError.NotFound(message = "Active API key $keyId not found for client $clientId")
            }
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.API_KEY_REVOKED,
                        entityType = AuditEntityType.API_KEY,
                        entityId = keyId,
                        summary = "Revoked API key $keyId",
                        details = mapOf("clientId" to clientId.toString(), "keyId" to keyId.toString()),
                    ),
            )
        }

    /**
     * Set (or clear, when [rateLimitPerMin] is null) a client's per-minute rate-limit override (#603).
     * ADMINISTRATOR-only; a positive value is required when present. Returns the refreshed client.
     */
    fun setRateLimit(
        token: VerifiedFirebaseToken,
        clientId: UUID,
        rateLimitPerMin: Int?,
    ): Either<ServiceError, ApiClientResponse> =
        either {
            val adminId = requireAdmin(token = token).bind()
            ensure(condition = rateLimitPerMin == null || rateLimitPerMin > 0) {
                ServiceError.Validation(message = "rateLimitPerMin must be positive")
            }
            val client =
                ensureNotNull(value = clients.setRateLimit(clientId = clientId, rateLimitPerMin = rateLimitPerMin)) {
                    ServiceError.NotFound(message = "API client $clientId not found")
                }.toDomain()
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.API_CLIENT_RATE_LIMIT_SET,
                        entityType = AuditEntityType.API_CLIENT,
                        entityId = clientId,
                        summary = "Set rate limit for ${client.name} to ${rateLimitPerMin ?: "default"}",
                        details =
                            mapOf(
                                "clientId" to clientId.toString(),
                                "rateLimitPerMin" to rateLimitPerMin?.toString(),
                            ),
                    ),
            )
            client.toResponse()
        }

    /**
     * The effective per-minute limit for a rate-limit bucket [key] (#603): a client's override when the
     * key is its id, else the [default] (also used for the anonymous per-host buckets). Read-only.
     */
    fun rateLimitForKey(
        key: String,
        default: Int,
    ): Int {
        val clientId = runCatching { UUID.fromString(key) }.getOrNull() ?: return default
        return clients.findClientById(id = clientId)?.client?.rateLimitPerMin ?: default
    }

    /**
     * Resolve a raw `X-Api-Key` value to a [ClientPrincipal] (#596). Never throws and never logs the
     * key. An empty value is [ClientAuthResult.Missing]; a malformed or unknown key is
     * [ClientAuthResult.Invalid] (→ 401); a known-but-revoked/expired key, or one whose client is
     * suspended, is [ClientAuthResult.Forbidden] (→ 403).
     */
    fun authenticate(rawKey: String): ClientAuthResult {
        val result = classifyKey(rawKey = rawKey)
        if (result is ClientAuthResult.Authenticated) {
            clients.touchLastUsed(keyId = result.principal.keyId, usedAt = LocalDateTime.now())
        }
        return result
    }

    /**
     * The client id behind a usable key (#598), for rate-limit keying. Read-only: unlike [authenticate]
     * it does not record last-used, so it's cheap to call in the rate-limit request-key extractor.
     */
    fun resolveClientId(rawKey: String): UUID? = (classifyKey(rawKey = rawKey) as? ClientAuthResult.Authenticated)?.principal?.clientId

    /** Classify a raw key with no side effects: Missing / Invalid / Forbidden, or Authenticated. */
    private fun classifyKey(rawKey: String): ClientAuthResult {
        val raw = rawKey.trim()
        return when {
            raw.isEmpty() -> ClientAuthResult.Missing
            !ApiKeyCrypto.looksValid(raw = raw) -> ClientAuthResult.Invalid
            else -> resolve(raw = raw)
        }
    }

    /**
     * Look up a well-formed key by hash and classify it: unknown → Invalid; revoked/expired or a
     * suspended client → Forbidden; otherwise Authenticated. Side-effect-free.
     */
    private fun resolve(raw: String): ClientAuthResult {
        val found = clients.findKeyByHash(hash = ApiKeyCrypto.hash(plaintext = raw)) ?: return ClientAuthResult.Invalid
        val resolved = found.first.toResolved(clientStatusRaw = found.second)
        val key = resolved.key
        val rejected =
            key.status == ApiKeyStatus.REVOKED ||
                key.expiresAt?.isBefore(LocalDateTime.now()) == true ||
                resolved.clientStatus == ApiClientStatus.SUSPENDED
        return if (rejected) {
            ClientAuthResult.Forbidden
        } else {
            ClientAuthResult.Authenticated(
                principal = ClientPrincipal(clientId = key.clientId, keyId = key.id, scopes = key.scopes),
            )
        }
    }

    /**
     * A public player directory (#597) for a machine-to-machine partner read. Only public fields
     * (public code + display name) are exposed. The caller's scope is enforced at the route.
     */
    fun playerDirectory(principal: ClientPrincipal): Either<ServiceError, List<PartnerPlayerResponse>> =
        either {
            // Least-privilege gate (#597): a key without the RESEARCHER scope is refused (403).
            ensure(condition = principal.hasScope(capability = Capability.RESEARCHER)) {
                ServiceError.Forbidden(message = "This API key is not scoped for 'RESEARCHER'")
            }
            users
                .search(
                    query =
                        UserSearchQuery(
                            name = null,
                            code = null,
                            q = null,
                            sex = null,
                            dobMin = null,
                            dobMax = null,
                            rating = null,
                        ),
                ).map { it.toDomain() }
                .map { PublicPlayer(publicCode = it.publicCode, displayName = it.displayName()).toResponse() }
        }

    /**
     * The capabilities [principal] may exercise on behalf of the user behind [token] (#597) — the
     * intersection of the key's scopes and the user's own capabilities. Forbidden if the token resolves
     * to no user (it always should on a Firebase-authenticated route).
     */
    fun effectiveCapabilities(
        token: VerifiedFirebaseToken,
        principal: ClientPrincipal,
    ): Either<ServiceError, ClientEffectiveCapabilitiesResponse> =
        either {
            val user = ensureNotNull(value = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()) { ServiceError.Forbidden() }
            ClientEffectiveCapabilities(
                clientId = principal.clientId,
                userId = user.id,
                capabilities = principal.effectiveCapabilities(userCapabilities = user.capabilities),
            ).toResponse()
        }

    /** The client-identity DTO for a resolved [principal] (#597), so the route maps nothing itself. */
    fun describePrincipal(principal: ClientPrincipal): ClientIdentityResponse = principal.toResponse()

    /** Parse a key scope name to a [Capability] (#225); an unknown name is a [ServiceError.Validation]. */
    private fun parseScope(raw: String): Either<ServiceError, Capability> =
        Capability.entries.find { it.name == raw }?.right()
            ?: ServiceError.Validation(
                message = "Invalid scope '$raw'; expected one of ${Capability.entries.joinToString { it.name }}",
            ).left()

    /** Parse a key environment name to an [ApiKeyEnvironment]; an unknown name is a [ServiceError.Validation]. */
    private fun parseEnvironment(raw: String): Either<ServiceError, ApiKeyEnvironment> =
        ApiKeyEnvironment.entries.find { it.name == raw }?.right()
            ?: ServiceError.Validation(
                message = "Invalid environment '$raw'; expected one of ${ApiKeyEnvironment.entries.joinToString { it.name }}",
            ).left()

    /** Access gate: the caller must be an ADMINISTRATOR. Returns the caller's id (the audit actor). */
    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
        val permitted = caller != null && caller.capabilities.contains(element = Capability.ADMINISTRATOR)
        return if (caller == null || !permitted) ServiceError.Forbidden().left() else caller.id.right()
    }
}
