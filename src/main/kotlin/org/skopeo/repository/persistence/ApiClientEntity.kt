// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of an `api_clients` row (#633): only the root-row scalar columns, no child
 * rows (keys live in `api_keys`) and no derived fields. Model-free leaf — [status] is held raw as a
 * String; the repository's `toDomain` parses it to the domain enum and attaches the loaded keys.
 */
data class ApiClientEntity(
    val id: UUID,
    val name: String,
    val status: String,
    val rateLimitPerMin: Int?,
    val createdBy: UUID?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
