// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

/**
 * Raw persistence graph of a durable event team (#720): the [team] row plus its separately-loaded
 * ordered [members] (from `event_team_members`). This is the shape `EventTeamRepository` returns —
 * only the repository can run the extra member query, so it bundles the children here and the
 * `mapper.entity` conversion builds the domain `EventTeam` with no further DB access. Kept
 * **model-free** so `persistence` stays a leaf.
 */
data class EventTeamAggregateEntity(
    val team: EventTeamEntity,
    val members: List<EventTeamMemberEntity>,
)
