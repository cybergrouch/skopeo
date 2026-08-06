// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.util.UUID

/**
 * Raw persistence graph of a club (#633): the [club] row plus its separately-loaded owner ids (from the
 * `club_owners` join table). This is the shape `ClubRepository` returns — only the repository can run the
 * extra query, so it bundles the children here and the `mapper.entity` conversion builds the domain `Club`
 * with no further DB access. Kept **model-free** (a raw [ClubEntity] + plain [UUID]s) so `persistence`
 * stays a leaf.
 */
data class ClubAggregateEntity(
    val club: ClubEntity,
    val ownerIds: List<UUID>,
)
