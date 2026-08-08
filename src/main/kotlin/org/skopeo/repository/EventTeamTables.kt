// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption

private const val EVENT_TEAM_NAME_MAX = 255

/** Durable, event-scoped organizational teams (#720): a name, scoped to an event (V41). */
internal object EventTeamsTable : UUIDTable(name = "event_teams") {
    val eventId = reference(name = "event_id", foreign = EventsTable, onDelete = ReferenceOption.CASCADE)
    val name = varchar(name = "name", length = EVENT_TEAM_NAME_MAX)
}

/**
 * Ordered members of an event team (#720): a user in a positional slot (1 / 2). `event_id` is
 * denormalized alongside `team_id` so exclusive membership (one team per event per user) is a plain
 * DB UNIQUE(event_id, user_id) constraint (V41), not merely an application check.
 */
internal object EventTeamMembersTable : UUIDTable(name = "event_team_members") {
    val teamId = reference(name = "team_id", foreign = EventTeamsTable, onDelete = ReferenceOption.CASCADE)
    val eventId = reference(name = "event_id", foreign = EventsTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference(name = "user_id", foreign = UsersTable, onDelete = ReferenceOption.CASCADE)
    val position = integer(name = "position")
}
