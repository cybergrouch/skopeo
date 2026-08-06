// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of an `audit_log` row (#633): the dumb, as-stored data with **no behaviour**.
 * A flat aggregate that mirrors `org.skopeo.model.AuditEntry` field-for-field, but keeps the columns
 * in their **stored** shape: [action] and [entityType] are the model enums as the raw `String` names
 * held in the DB (parsed via `valueOf` in `toDomain`), and [details] is the JSON blob already decoded
 * into its plain `Map<String, String?>` form. Kept **model-free** (only stdlib types) so `persistence`
 * stays a leaf package — the repository maps a DB row to this, then converts it to the domain
 * `AuditEntry` at a single boundary (`AuditEntryEntity.toDomain`).
 */
data class AuditEntryEntity(
    val id: UUID,
    val occurredAt: LocalDateTime,
    val actorUserId: UUID?,
    val action: String,
    val entityType: String,
    val entityId: UUID?,
    val summary: String,
    val details: Map<String, String?>,
    val comment: String?,
    val actorClientId: UUID?,
)
