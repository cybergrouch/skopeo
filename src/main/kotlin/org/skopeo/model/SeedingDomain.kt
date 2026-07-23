// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * A host-curated, named roster of players (issue #111). Members reference users; a list can be
 * (re)generated into a [Seeding]. Owned by the host who created it.
 */
data class PlayerList(
    val id: UUID,
    val ownerId: UUID,
    val name: String,
    val createdAt: LocalDateTime,
    val memberUserIds: List<UUID>,
)

/**
 * A timestamped, immutable snapshot generated from a [PlayerList] (issue #111): the members that have
 * a rating, sorted highest-first, with the top half seeded. Regenerating a list overwrites its seeding.
 */
data class Seeding(
    val id: UUID,
    val listId: UUID,
    val generatedAt: LocalDateTime,
    val entries: List<SeedingEntry>,
)

/**
 * One row of a [Seeding], captured at generation time so the CSV is reproducible. [seed] is the
 * bracket seed for the top half (1..⌈N/2⌉) and null for the unseeded bottom half; [position] is the
 * overall 1-based rank; [rating] is the exact rating snapshot (staff-only context).
 */
data class SeedingEntry(
    val seed: Int?,
    val position: Int,
    // Null only if the snapshotted user was later deleted; the captured name/code/rating still stand.
    val userId: UUID?,
    val displayName: String?,
    val publicCode: String,
    val ntrpBand: String?,
    val rating: String,
    val sex: String?,
    val age: Int?,
    // True for a login-less, not-yet-claimed placeholder ("dummy") player (#496/#505): the seeding view
    // renders an "Unclaimed" tag beside the name. Resolved from the live user at read time (the snapshot
    // table doesn't persist it). Real/claimed players leave it false.
    val placeholder: Boolean = false,
    // True for an admin-soft-deleted account (#518): the seeding view renders a dominant "Deleted" chip.
    // Resolved from the live user at read time, like [placeholder].
    val deleted: Boolean = false,
)
