// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.util.UUID

/**
 * Raw persistence graph of a player list (#111/#633): the [list] row plus its separately-loaded member
 * ids (from the `player_list_members` join table). The shape `PlayerListRepository` returns — only the
 * repository can run the extra query, so it bundles the members here and the `mapper.entity` conversion
 * builds the domain `PlayerList` with no further DB access. Kept **model-free** so `persistence` stays a
 * leaf.
 */
data class PlayerListAggregateEntity(
    val list: PlayerListEntity,
    val memberUserIds: List<UUID>,
)
