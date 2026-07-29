// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.audit

import org.skopeo.dto.audit.AuditEntryResponse
import org.skopeo.dto.audit.AuditLogResponse
import org.skopeo.dto.audit.AuditMatchResponse
import org.skopeo.dto.audit.AuditPersonResponse
import org.skopeo.model.AuditEntryView
import org.skopeo.model.AuditLogViewPage
import org.skopeo.model.AuditMatchRef
import org.skopeo.model.AuditPersonRef
import org.skopeo.model.category
import java.time.ZoneOffset

fun AuditLogViewPage.toResponse(): AuditLogResponse = AuditLogResponse(items = items.map { it.toResponse() }, total = total)

private fun AuditEntryView.toResponse(): AuditEntryResponse =
    AuditEntryResponse(
        id = entry.id.toString(),
        occurredAt = entry.occurredAt.toInstant(ZoneOffset.UTC).toString(),
        category = entry.action.category.name,
        action = entry.action.name,
        entityType = entry.entityType.name,
        entityId = entry.entityId?.toString(),
        summary = entry.summary,
        details = entry.details,
        comment = entry.comment,
        actor = actor?.toResponse(),
        target = target?.toResponse(),
        matchTarget = matchTarget?.toResponse(),
    )

private fun AuditPersonRef.toResponse(): AuditPersonResponse =
    AuditPersonResponse(
        userId = userId.toString(),
        displayName = displayName,
        publicCode = publicCode,
        isPlaceholder = placeholder,
        isDeleted = deleted,
    )

private fun AuditMatchRef.toResponse(): AuditMatchResponse =
    AuditMatchResponse(matchId = matchId.toString(), publicCode = publicCode, matchDate = matchDate.toString())
