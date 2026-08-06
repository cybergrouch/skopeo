// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.entity.seeding

import org.skopeo.domain.model.PlayerList
import org.skopeo.repository.persistence.PlayerListAggregateEntity

/**
 * Entity→domain mapper (#633): builds the domain [PlayerList] from the raw [PlayerListAggregateEntity]
 * graph the repository returns (the `player_lists` row plus its loaded member ids). Lives in
 * `mapper.entity` (which may depend on both `persistence` and `model`); the service calls it, since
 * `repository ↛ mapper`.
 */
fun PlayerListAggregateEntity.toDomain(): PlayerList =
    PlayerList(
        id = list.id,
        ownerId = list.ownerId,
        name = list.name,
        createdAt = list.createdAt,
        memberUserIds = memberUserIds,
    )
