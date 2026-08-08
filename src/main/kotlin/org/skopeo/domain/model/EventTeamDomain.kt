// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.model

import java.util.UUID

/**
 * A durable, event-scoped organizational team (#720): a named grouping of an event's participants.
 * Purely organizational — it does NOT affect rating calculation or seeding (both stay player-based).
 * "Durable" means the membership is stable during the event; a team is considered dissolved once the
 * event is over. Fixtures snapshot player ids at creation, so editing/dissolving a team later never
 * rewrites the fixtures it helped populate. [members] are ordered by their slot [EventTeamMember.position].
 */
data class EventTeam(
    val id: UUID,
    val eventId: UUID,
    val name: String,
    val members: List<EventTeamMember>,
)

/** One member of an [EventTeam] (#720): the user in a positional slot (1 = Player 1, 2 = Player 2). */
data class EventTeamMember(
    val userId: UUID,
    val position: Int,
)

/** Everything needed to create a durable event team (#720): the event, an optional name, ordered members. */
data class CreateEventTeamCommand(
    val eventId: UUID,
    val name: String,
    // Member user ids in slot order (index 0 → position 1, index 1 → position 2).
    val memberUserIds: List<UUID>,
)

/** Everything needed to update a durable event team (#720): the team, its new name, and ordered members. */
data class UpdateEventTeamCommand(
    val teamId: UUID,
    val name: String,
    val memberUserIds: List<UUID>,
)

/**
 * An [EventTeam] with its members resolved to display facets (#720) — the shape the API returns. Each
 * member carries a name + shareable code and the placeholder/deleted flags, mirroring the roster view.
 */
data class EventTeamView(
    val team: EventTeam,
    val members: List<EventTeamMemberRef>,
)

/** A resolved member of an event team (#720): the positional slot plus display name/code + flags. */
data class EventTeamMemberRef(
    val userId: UUID,
    val position: Int,
    val displayName: String?,
    val publicCode: String?,
    val placeholder: Boolean = false,
    val deleted: Boolean = false,
)
