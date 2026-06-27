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

/** The kind of entity an [AuditAction] concerns. */
enum class AuditEntityType {
    USER,
    RATING,
    CAPABILITY,
    INVITE,
    MATCH,
}

/**
 * The coarse grouping the trace viewer (#102) shows as one table each. Several categories have no
 * recorded action yet — their events are wired in a follow-up, so those tables read empty for now.
 */
enum class AuditCategory {
    USER_CREATION,
    NAME_CHANGE,
    CONTACT_CHANGE,
    INVITE,
    MATCH_FIXTURE,
    MATCH_RESULT,
    CAPABILITY_CHANGE,
    RATING_CHANGE,
}

/** The category an action rolls up into. */
val AuditAction.category: AuditCategory
    get() =
        when (this) {
            AuditAction.CAPABILITY_GRANTED, AuditAction.CAPABILITY_REVOKED -> AuditCategory.CAPABILITY_CHANGE
            AuditAction.RATING_SET, AuditAction.RATING_OVERRIDDEN -> AuditCategory.RATING_CHANGE
            AuditAction.INVITE_CREATED, AuditAction.INVITE_REVOKED -> AuditCategory.INVITE
        }

/** The actions that roll up into a category (empty for categories whose events aren't wired yet). */
fun AuditCategory.actions(): List<AuditAction> = AuditAction.entries.filter { it.category == this }

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

/** A user id resolved to a display name + player code, for human-readable trace rendering (#102). */
data class AuditPersonRef(
    val userId: UUID,
    val displayName: String?,
    val publicCode: String?,
)

/** An audit entry with its actor (and, where the entity is a user, its target) resolved to names. */
data class AuditEntryView(
    val entry: AuditEntry,
    val actor: AuditPersonRef?,
    val target: AuditPersonRef?,
)

/** A page of resolved audit views (newest first) plus the total, for the trace viewer. */
data class AuditLogViewPage(
    val items: List<AuditEntryView>,
    val total: Int,
)
