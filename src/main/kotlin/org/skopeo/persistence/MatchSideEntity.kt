// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.persistence

import java.util.UUID

/**
 * Raw persistence view of one side of a match (#633): a (temporary) [teamId] and its participating
 * [userIds] in position order, loaded from the `team_users` join. The dumb, as-stored data with no
 * behaviour; it is assembled into the domain `org.skopeo.model.MatchSide` at the `MatchAggregateEntity`
 * conversion boundary in `mapper.entity`. Kept **model-free** so `persistence` stays a leaf.
 */
data class MatchSideEntity(
    val teamId: UUID,
    val userIds: List<UUID>,
)
