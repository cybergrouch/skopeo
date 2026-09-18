// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.util.UUID

/**
 * Raw persistence view of one side of a match (#633): a (temporary) [teamId] and its participating
 * [userIds] in position order, loaded from the `team_users` join. The dumb, as-stored data with no
 * behaviour; it is assembled into the domain `org.skopeo.domain.model.MatchSide` at the `MatchAggregateEntity`
 * conversion boundary in `mapper.entity`. Kept **model-free** so `persistence` stays a leaf.
 */
data class MatchSideEntity(
    val teamId: UUID,
    val userIds: List<UUID>,
    // The team's stored name (#1079). Always present — `teams.name` is NOT NULL — but its nature
    // depends on [isStanding], which is why both travel together rather than a single "display me" field.
    val name: String = "",
    // False for the ad-hoc team a fixture creates, true for a standing event team (#720). The
    // distinction decides whether the name is worth showing; see the domain model's note.
    val isStanding: Boolean = false,
)
