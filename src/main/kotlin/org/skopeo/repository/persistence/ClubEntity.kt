// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.util.UUID

/**
 * Raw persistence view of a `clubs` row (#633): only the root-row scalar columns, no child rows
 * (owner ids live in `club_owners`) and no derived fields. Model-free leaf — the repository's
 * `toDomain` attaches the separately-loaded owner ids to build the domain `Club`.
 */
data class ClubEntity(
    val id: UUID,
    val name: String,
    val publicCode: String,
    val isActive: Boolean,
    val tournamentsSanctioned: Boolean,
    val createdBy: UUID?,
)
