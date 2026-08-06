// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.entity.audit

import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditEntry
import org.skopeo.persistence.AuditEntryEntity

/**
 * Entity→domain mapper (#633): builds the domain [AuditEntry] from the raw persistence [AuditEntryEntity]
 * the repository returns. Parses the stored [action]/[entityType] enum names via `valueOf` and passes the
 * already-decoded [details] JSON map through. Lives in `mapper.entity` (which may depend on both
 * `persistence` and `model`); the service calls it, since `repository ↛ mapper`.
 */
fun AuditEntryEntity.toDomain(): AuditEntry =
    AuditEntry(
        id = id,
        occurredAt = occurredAt,
        actorUserId = actorUserId,
        actorClientId = actorClientId,
        action = AuditAction.valueOf(value = action),
        entityType = AuditEntityType.valueOf(value = entityType),
        entityId = entityId,
        summary = summary,
        details = details,
        comment = comment,
    )
