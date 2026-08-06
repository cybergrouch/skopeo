// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.persistence

import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of an `api_keys` row (#633): only the as-stored scalar columns, no derived
 * fields. Model-free leaf — [scopes] stays the raw comma-separated column and [status] the raw String;
 * the repository's `toDomain` parses scopes into the domain `Set<Capability>` and status into its enum.
 */
data class ApiKeyEntity(
    val id: UUID,
    val clientId: UUID,
    val keyPrefix: String,
    val keyHash: String,
    val scopes: String,
    val status: String,
    val createdBy: UUID?,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime?,
    val lastUsedAt: LocalDateTime?,
    val revokedAt: LocalDateTime?,
)
