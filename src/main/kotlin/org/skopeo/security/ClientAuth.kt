// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.security

import org.skopeo.model.Capability
import java.util.UUID

/**
 * The resolved caller behind a valid API key, attached to the call for downstream authorization. Holds
 * no secret — only the ids and the granted scopes. A transport-boundary auth type, kept out of `model`
 * so routes can consume it without depending on the domain.
 */
data class ClientPrincipal(
    val clientId: UUID,
    val keyId: UUID,
    val scopes: Set<Capability>,
)

/** Whether a key is authorized for [capability] (least-privilege scope check, #597). */
fun ClientPrincipal.hasScope(capability: Capability): Boolean = capability in scopes

/**
 * The capabilities a partner may exercise **on behalf of a user** (#597): the intersection of the key's
 * scopes and the acting user's own capabilities. The app can never do more than either party allows.
 */
fun ClientPrincipal.effectiveCapabilities(userCapabilities: Set<Capability>): Set<Capability> = scopes intersect userCapabilities

/**
 * The outcome of resolving an `X-Api-Key` header. Kept HTTP-free (the route layer maps it to a status):
 * [Missing]/[Invalid] → 401 (no or unusable credential), [Forbidden] → 403 (a known key that is
 * revoked/expired, or whose client is suspended).
 */
sealed interface ClientAuthResult {
    data class Authenticated(
        val principal: ClientPrincipal,
    ) : ClientAuthResult

    data object Missing : ClientAuthResult

    data object Invalid : ClientAuthResult

    data object Forbidden : ClientAuthResult
}
