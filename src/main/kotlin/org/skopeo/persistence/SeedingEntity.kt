// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.persistence

import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of a `seedings` row (#633): only the root-row scalar columns, no child rows
 * (the frozen entries live in `seeding_entries`) and no derived fields. Model-free leaf — the
 * repository's `toDomain` attaches the separately-loaded, status-resolved entries to build the
 * domain `Seeding`. The child entries stay domain and are assembled by the repository because their
 * `placeholder`/`deleted` flags (and name/rating context) are resolved on read from the live user
 * rows, so there is no clean single-row entity to extract for them.
 */
data class SeedingEntity(
    val id: UUID,
    val listId: UUID,
    val generatedAt: LocalDateTime,
    // Persisted audit column; not surfaced by the domain `Seeding`, kept here for a faithful raw view.
    val generatedBy: UUID?,
)
