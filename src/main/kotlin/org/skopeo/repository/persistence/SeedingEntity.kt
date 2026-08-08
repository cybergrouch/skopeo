// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of a `seedings` root row (#633): only the root-row scalar columns, no child
 * rows (the frozen entries live in `seeding_entries`, modelled by [SeedingEntryEntity]) and no derived
 * fields. Model-free leaf — the repository bundles this with the separately-loaded, status-resolved
 * entries into a [SeedingAggregateEntity], and the `mapper.entity` `toDomain` builds the domain `Seeding`.
 */
data class SeedingEntity(
    val id: UUID,
    // Exactly one of [listId] / [eventId] is set — the seeding's source (a player list, #111, or an
    // event's participants, #714).
    val listId: UUID?,
    val eventId: UUID?,
    val generatedAt: LocalDateTime,
    // Persisted audit column; not surfaced by the domain `Seeding`, kept here for a faithful raw view.
    val generatedBy: UUID?,
    // True when the host drag-reordered and saved this seeding by hand (#718); false for a generated one.
    val manuallyEdited: Boolean,
)
