// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of a `duplicate_candidates` row (#633): the dumb, as-stored data with **no
 * behaviour**. A flat aggregate — no child rows and no derived fields — so it mirrors the domain
 * `org.skopeo.domain.model.DuplicateCandidate` field-for-field, except the two enums ([signal], [status]) are
 * held as their **raw stored `String`** and parsed at the boundary. Kept **model-free** (only stdlib
 * types) so `persistence` stays a leaf package — the repository maps a DB row to this, then converts it to
 * the domain `DuplicateCandidate` at a single boundary (`DuplicateCandidateEntity.toDomain`).
 */
data class DuplicateCandidateEntity(
    val id: UUID,
    val userAId: UUID,
    val userBId: UUID,
    val signal: String,
    val detail: String?,
    val status: String,
    val flaggedBy: UUID?,
    val flaggedAt: LocalDateTime,
    val resolvedBy: UUID?,
    val resolvedAt: LocalDateTime?,
)
