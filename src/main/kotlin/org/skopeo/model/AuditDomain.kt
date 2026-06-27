// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * Append-only provenance of domain actions (issue #100): who did what, when, to which entity. A
 * single audit log captures every traceable action; domain tables don't reference it.
 */
enum class AuditAction {
    CAPABILITY_GRANTED,
    CAPABILITY_REVOKED,
    RATING_SET,
    RATING_OVERRIDDEN,
    INVITE_CREATED,
    INVITE_REVOKED,
}

/** The kind of entity an [AuditAction] concerns — the coarse category the trace viewer groups by. */
enum class AuditEntityType {
    USER,
    RATING,
    CAPABILITY,
    INVITE,
    MATCH,
}

/**
 * One provenance record to append. [actorUserId] is the user who performed the action, or null for a
 * SYSTEM / self-driven action. [summary] is a short human-readable description; [details] carries
 * per-action extras (e.g. before/after values) and is stored as JSON.
 */
data class AuditWrite(
    val actorUserId: UUID?,
    val action: AuditAction,
    val entityType: AuditEntityType,
    val entityId: UUID?,
    val summary: String,
    val details: Map<String, String?> = emptyMap(),
)

/**
 * A stored provenance record. [summary] is system-generated (never user input); [comment] is an
 * optional free-text note an administrator can attach later — the only mutable, un-audited field.
 */
data class AuditEntry(
    val id: UUID,
    val occurredAt: LocalDateTime,
    val actorUserId: UUID?,
    val action: AuditAction,
    val entityType: AuditEntityType,
    val entityId: UUID?,
    val summary: String,
    val details: Map<String, String?>,
    val comment: String?,
)

/** A page of audit entries (newest first) plus the total matching the filter (for pagination). */
data class AuditLogPage(
    val items: List<AuditEntry>,
    val total: Int,
)
