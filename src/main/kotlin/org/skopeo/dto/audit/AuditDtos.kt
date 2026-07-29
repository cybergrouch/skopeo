// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.audit

import kotlinx.serialization.Serializable

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
    // True for a login-less, not-yet-claimed placeholder ("dummy") player (#496/#505): the activity log
    // renders an "Unclaimed" tag beside the name. Real/claimed users leave it false.
    val isPlaceholder: Boolean = false,
    // True for an admin-soft-deleted account (#518): the activity log renders a dominant "Deleted" chip.
    val isDeleted: Boolean = false,
)

/** A match-typed target resolved to its public code + date, so the row links to the public match page (#136). */
@Serializable
data class AuditMatchResponse(
    val matchId: String,
    val publicCode: String,
    val matchDate: String,
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
    val matchTarget: AuditMatchResponse? = null,
)

@Serializable
data class AuditLogResponse(
    val items: List<AuditEntryResponse>,
    val total: Int,
)
