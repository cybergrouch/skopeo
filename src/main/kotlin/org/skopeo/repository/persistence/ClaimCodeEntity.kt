// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of a `placeholder_claim_codes` row (#633): the dumb, as-stored data with **no
 * behaviour**. A flat aggregate — no child rows and no derived fields. Kept **model-free** (only stdlib
 * types) so `persistence` stays a leaf package: enum-valued columns are stored RAW as their persisted
 * `String` ([status]) and parsed into the domain enum only at the boundary (`ClaimCodeEntity.toDomain`),
 * which produces the domain `org.skopeo.domain.model.ClaimCode`.
 */
data class ClaimCodeEntity(
    val id: UUID,
    val placeholderUserId: UUID,
    val codeHash: String,
    val expiresAt: LocalDateTime,
    // Raw stored String for the ClaimCodeStatus enum — parsed in toDomain to keep persistence model-free.
    val status: String,
    val createdBy: UUID?,
    val createdAt: LocalDateTime,
    val consumedAt: LocalDateTime?,
    val consumedBy: UUID?,
)
