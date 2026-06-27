// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.audit

import kotlinx.serialization.Serializable
import org.skopeo.model.AuditEntryView
import org.skopeo.model.AuditLogViewPage
import org.skopeo.model.AuditPersonRef
import org.skopeo.model.category
import java.time.ZoneOffset

/** Body for `PATCH /api/v1/audit/{id}/comment` — set/clear an administrator's note. */
@Serializable
data class AuditCommentRequest(
    val comment: String? = null,
)

/** A user resolved to a display name + player code (or just the id if unresolved). */
@Serializable
data class AuditPersonResponse(
    val userId: String,
    val displayName: String? = null,
    val publicCode: String? = null,
)

@Serializable
data class AuditEntryResponse(
    val id: String,
    // ISO-8601 instant in UTC (the client renders it in the viewer's timezone).
    val occurredAt: String,
    val category: String,
    val action: String,
    val entityType: String,
    val entityId: String? = null,
    val summary: String,
    val details: Map<String, String?> = emptyMap(),
    val comment: String? = null,
    val actor: AuditPersonResponse? = null,
    val target: AuditPersonResponse? = null,
)

@Serializable
data class AuditLogResponse(
    val items: List<AuditEntryResponse>,
    val total: Int,
)

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
    )

private fun AuditPersonRef.toResponse(): AuditPersonResponse =
    AuditPersonResponse(userId = userId.toString(), displayName = displayName, publicCode = publicCode)
