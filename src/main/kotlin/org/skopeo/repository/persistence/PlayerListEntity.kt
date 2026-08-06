// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of a `player_lists` row (#633): only the root-row scalar columns, no child rows
 * (member user ids live in `player_list_members`) and no derived fields. Model-free leaf — the
 * repository's `toDomain` attaches the separately-loaded member ids to build the domain `PlayerList`.
 */
data class PlayerListEntity(
    val id: UUID,
    val ownerId: UUID,
    val name: String,
    val createdAt: LocalDateTime,
)
