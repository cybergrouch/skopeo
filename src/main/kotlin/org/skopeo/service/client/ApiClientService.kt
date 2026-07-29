// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.client

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.skopeo.model.ApiClient
import org.skopeo.model.ApiClientStatus
import org.skopeo.model.ApiKeyEnvironment
import org.skopeo.model.ApiKeyStatus
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.Capability
import org.skopeo.model.ClientAuthResult
import org.skopeo.model.ClientPrincipal
import org.skopeo.model.InsertApiKeyCommand
import org.skopeo.model.IssuedApiKey
import org.skopeo.model.ResolvedApiKey
import org.skopeo.model.ServiceError
import org.skopeo.repository.ApiClientRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import org.skopeo.service.user.VerifiedFirebaseToken
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
class ApiClientService(
    private val clients: ApiClientRepository = ApiClientRepository(),
    private val users: UserRepository = UserRepository(),
    private val audit: AuditService = AuditService(),
) {
    fun createClient(
        token: VerifiedFirebaseToken,
        name: String,
    ): Either<ServiceError, ApiClient> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val trimmed = name.trim()
            ensure(condition = trimmed.isNotBlank()) { ServiceError.Validation(message = "Client name is required") }
            ensure(condition = trimmed.length <= CLIENT_NAME_MAX) {
                ServiceError.Validation(message = "Client name must be at most $CLIENT_NAME_MAX characters")
            }
            val client = clients.createClient(name = trimmed, createdBy = adminId)
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
            client
        }

    fun listClients(token: VerifiedFirebaseToken): Either<ServiceError, List<ApiClient>> =
        either {
            requireAdmin(token = token).bind()
            clients.listClients()
        }

    /**
     * Issue a new key for [clientId]. Returns the [IssuedApiKey] whose plaintext must be shown to the
     * admin exactly once. [expiresInDays], when set, bounds the key's lifetime.
     */
    fun issueKey(
        token: VerifiedFirebaseToken,
        clientId: UUID,
        scopes: Set<Capability>,
        environment: ApiKeyEnvironment,
        expiresInDays: Long?,
    ): Either<ServiceError, IssuedApiKey> =
        either {
            val adminId = requireAdmin(token = token).bind()
            ensure(condition = expiresInDays == null || expiresInDays > 0) {
                ServiceError.Validation(message = "expiresInDays must be positive")
            }
            val client =
                ensureNotNull(value = clients.findClientById(id = clientId)) {
                    ServiceError.NotFound(message = "API client $clientId not found")
                }
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
                )
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
            IssuedApiKey(client = client, key = key, plaintext = generated.plaintext)
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
     * Resolve a raw `X-Api-Key` value to a [ClientPrincipal] (#596). Never throws and never logs the
     * key. An empty value is [ClientAuthResult.Missing]; a malformed or unknown key is
     * [ClientAuthResult.Invalid] (→ 401); a known-but-revoked/expired key, or one whose client is
     * suspended, is [ClientAuthResult.Forbidden] (→ 403).
     */
    fun authenticate(rawKey: String): ClientAuthResult {
        val raw = rawKey.trim()
        return when {
            raw.isEmpty() -> ClientAuthResult.Missing
            !ApiKeyCrypto.looksValid(raw = raw) -> ClientAuthResult.Invalid
            else -> resolve(raw = raw)
        }
    }

    /** Look up a well-formed key by hash and classify it (unknown → Invalid; else via [classify]). */
    private fun resolve(raw: String): ClientAuthResult {
        val resolved = clients.findKeyByHash(hash = ApiKeyCrypto.hash(plaintext = raw)) ?: return ClientAuthResult.Invalid
        return classify(resolved = resolved)
    }

    /** A recognized key: reject if revoked/expired or its client is suspended; else authenticate. */
    private fun classify(resolved: ResolvedApiKey): ClientAuthResult {
        val key = resolved.key
        val rejected =
            key.status == ApiKeyStatus.REVOKED ||
                key.expiresAt?.isBefore(LocalDateTime.now()) == true ||
                resolved.clientStatus == ApiClientStatus.SUSPENDED
        return if (rejected) {
            ClientAuthResult.Forbidden
        } else {
            clients.touchLastUsed(keyId = key.id, usedAt = LocalDateTime.now())
            ClientAuthResult.Authenticated(
                principal = ClientPrincipal(clientId = key.clientId, keyId = key.id, scopes = key.scopes),
            )
        }
    }

    /** Access gate: the caller must be an ADMINISTRATOR. Returns the caller's id (the audit actor). */
    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        val permitted = caller != null && caller.capabilities.contains(element = Capability.ADMINISTRATOR)
        return if (caller == null || !permitted) ServiceError.Forbidden().left() else caller.id.right()
    }
}
