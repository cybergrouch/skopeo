// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.dto.event

import kotlinx.serialization.Serializable

/**
 * Body for `POST /api/v1/events/{id}/teams` — create a durable event team (#720). [memberUserIds] are
 * the team's members in slot order (1 for a singles event, 2 for doubles/mixed). [name] is optional —
 * omit it to auto-name from the members' display names; supply it to override.
 */
@Serializable
data class CreateEventTeamRequest(
    val memberUserIds: List<String>,
    val name: String? = null,
)

/**
 * Body for `PATCH /api/v1/events/{id}/teams/{teamId}` — update a durable event team (#720). Fields left
 * null are unchanged: [memberUserIds] replaces the roster (in slot order) when present; [name] renames
 * (or, when set to a blank string, is treated as "re-auto-name from members").
 */
@Serializable
data class UpdateEventTeamRequest(
    val memberUserIds: List<String>? = null,
    val name: String? = null,
)

/** One resolved member of an event team (#720): the positional slot + display name/code and flags. */
@Serializable
data class EventTeamMemberResponse(
    val userId: String,
    val position: Int,
    val displayName: String? = null,
    val publicCode: String? = null,
    // True for a login-less, not-yet-claimed placeholder ("dummy") player (#496/#505).
    val isPlaceholder: Boolean = false,
    // True for an admin-soft-deleted account (#518).
    val isDeleted: Boolean = false,
)

/** A durable event team (#720): its id, owning event, name, and ordered members. */
@Serializable
data class EventTeamResponse(
    val id: String,
    val eventId: String,
    val name: String,
    val members: List<EventTeamMemberResponse>,
)
