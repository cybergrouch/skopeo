// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.util.UUID

/**
 * Raw persistence view of one `event_team_members` row (#720): a member [userId] and their ordered
 * [position] (slot 1 / slot 2) within the team. The dumb as-stored data with no behaviour; assembled
 * into the domain `EventTeamMember` at the [EventTeamAggregateEntity] conversion boundary in
 * `mapper.entity`. Kept **model-free** so `persistence` stays a leaf.
 */
data class EventTeamMemberEntity(
    val userId: UUID,
    val position: Int,
)
