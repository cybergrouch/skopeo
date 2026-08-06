// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.persistence

import org.skopeo.common.security.Capability
import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of a `user_capabilities` row (#633); flat aggregate, field-for-field copy; model-free leaf.
 *
 * [capability] is held as the `org.skopeo.common.security.Capability` enum (a common type, allowed here) — the raw
 * `String` column is parsed on read in the repository.
 */
data class CapabilityGrantEntity(
    val id: UUID,
    val userId: UUID,
    val capability: Capability,
    val isActive: Boolean,
    val grantedBy: UUID?,
    val grantedAt: LocalDateTime?,
    val revokedBy: UUID?,
    val revokedAt: LocalDateTime?,
)
