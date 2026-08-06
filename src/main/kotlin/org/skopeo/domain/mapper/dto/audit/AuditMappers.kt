// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.audit

import org.skopeo.common.dto.audit.AuditEntryResponse
import org.skopeo.common.dto.audit.AuditLogResponse
import org.skopeo.common.dto.audit.AuditMatchResponse
import org.skopeo.common.dto.audit.AuditPersonResponse
import org.skopeo.domain.model.AuditEntryView
import org.skopeo.domain.model.AuditLogViewPage
import org.skopeo.domain.model.AuditMatchRef
import org.skopeo.domain.model.AuditPersonRef
import org.skopeo.domain.model.category
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
