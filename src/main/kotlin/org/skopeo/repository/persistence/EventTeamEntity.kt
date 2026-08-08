// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.util.UUID

/**
 * Raw persistence view of an `event_teams` row (#720): the durable, event-scoped team's scalar columns
 * only — no members (those live in `event_team_members`). Model-free leaf; the repository bundles this
 * row with its separately-loaded ordered members into an [EventTeamAggregateEntity], and the
 * `mapper.entity` conversion builds the domain `EventTeam` with no further DB access.
 */
data class EventTeamEntity(
    val id: UUID,
    val eventId: UUID,
    val name: String,
)
