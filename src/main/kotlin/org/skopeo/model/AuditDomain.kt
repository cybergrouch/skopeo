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
    USER_CREATED,
    NAME_ADDED,
    NAME_UPDATED,
    CONTACT_ADDED,
    CONTACT_UPDATED,
    CAPABILITY_GRANTED,
    CAPABILITY_REVOKED,
    RATING_SET,
    RATING_OVERRIDDEN,
    RATING_REREQUESTED,
    RATING_REQUEST_DENIED,
    INVITE_CREATED,
    INVITE_REVOKED,
    MATCH_FIXTURE_CREATED,
    MATCH_RESULT_RECORDED,
    EVENT_CREATED,
    EVENT_RENAMED,
    EVENT_CLUB_CHANGED,
    EVENT_DELETED,
    RATING_CALCULATION_PREVIEWED,
    RATING_CALCULATION_MATCH_RATED,
    RATING_CALCULATION_COMMITTED,
    USER_MARKED_DUPLICATE,
    USER_UNMARKED_DUPLICATE,
    DUPLICATE_CANDIDATE_FLAGGED,
    DUPLICATE_CANDIDATE_DISMISSED,
    DUPLICATE_CANDIDATE_CONFIRMED,
    CLUB_CREATED,
    CLUB_RENAMED,
    CLUB_DELETED,
    CLUB_OWNER_ASSIGNED,
    CLUB_OWNER_REMOVED,
    SETTINGS_THEME_CHANGED,
}

/** The kind of entity an [AuditAction] concerns. */
enum class AuditEntityType {
    USER,
    RATING,
    CAPABILITY,
    INVITE,
    MATCH,
    CLUB,
    EVENT,
    CALCULATION,
    SETTING,
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
    RATING_CALCULATION,
    DUPLICATE_RECTIFICATION,
    CLUB_MANAGEMENT,
    EVENT_MANAGEMENT,
    SETTINGS_MANAGEMENT,
}

/** The category an action rolls up into. */
val AuditAction.category: AuditCategory
    get() =
        when (this) {
            AuditAction.USER_CREATED -> AuditCategory.USER_CREATION
            AuditAction.NAME_ADDED, AuditAction.NAME_UPDATED -> AuditCategory.NAME_CHANGE
            AuditAction.CONTACT_ADDED, AuditAction.CONTACT_UPDATED -> AuditCategory.CONTACT_CHANGE
            AuditAction.CAPABILITY_GRANTED, AuditAction.CAPABILITY_REVOKED -> AuditCategory.CAPABILITY_CHANGE
            AuditAction.RATING_SET,
            AuditAction.RATING_OVERRIDDEN,
            AuditAction.RATING_REREQUESTED,
            AuditAction.RATING_REQUEST_DENIED,
            -> AuditCategory.RATING_CHANGE
            AuditAction.INVITE_CREATED, AuditAction.INVITE_REVOKED -> AuditCategory.INVITE
            AuditAction.MATCH_FIXTURE_CREATED -> AuditCategory.MATCH_FIXTURE
            AuditAction.MATCH_RESULT_RECORDED -> AuditCategory.MATCH_RESULT
            AuditAction.EVENT_CREATED,
            AuditAction.EVENT_RENAMED,
            AuditAction.EVENT_CLUB_CHANGED,
            AuditAction.EVENT_DELETED,
            -> AuditCategory.EVENT_MANAGEMENT
            AuditAction.RATING_CALCULATION_PREVIEWED,
            AuditAction.RATING_CALCULATION_MATCH_RATED,
            AuditAction.RATING_CALCULATION_COMMITTED,
            -> AuditCategory.RATING_CALCULATION
            AuditAction.USER_MARKED_DUPLICATE,
            AuditAction.USER_UNMARKED_DUPLICATE,
            AuditAction.DUPLICATE_CANDIDATE_FLAGGED,
            AuditAction.DUPLICATE_CANDIDATE_DISMISSED,
            AuditAction.DUPLICATE_CANDIDATE_CONFIRMED,
            -> AuditCategory.DUPLICATE_RECTIFICATION
            AuditAction.CLUB_CREATED,
            AuditAction.CLUB_RENAMED,
            AuditAction.CLUB_DELETED,
            AuditAction.CLUB_OWNER_ASSIGNED,
            AuditAction.CLUB_OWNER_REMOVED,
            -> AuditCategory.CLUB_MANAGEMENT
            AuditAction.SETTINGS_THEME_CHANGED -> AuditCategory.SETTINGS_MANAGEMENT
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

/** A match id resolved to its public code + date, so a match-targeted entry can link to its page (#136). */
data class AuditMatchRef(
    val matchId: UUID,
    val publicCode: String,
    val matchDate: java.time.LocalDate,
)

/**
 * An audit entry with its actor resolved to a name, and its target resolved to either a user
 * ([target]) or a match ([matchTarget]) — whichever the entity type is — for clickable trace rows.
 */
data class AuditEntryView(
    val entry: AuditEntry,
    val actor: AuditPersonRef?,
    val target: AuditPersonRef?,
    val matchTarget: AuditMatchRef? = null,
)

/** A page of resolved audit views (newest first) plus the total, for the trace viewer. */
data class AuditLogViewPage(
    val items: List<AuditEntryView>,
    val total: Int,
)
