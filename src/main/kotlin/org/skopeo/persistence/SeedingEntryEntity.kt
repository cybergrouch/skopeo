// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.persistence

import java.util.UUID

/**
 * Raw persistence view of one `seeding_entries` row (#633): the frozen snapshot columns plus the two
 * live-status flags the repository resolves on read. Model-free leaf — the `mapper.entity` conversion
 * builds the domain `SeedingEntry` from this.
 *
 * [placeholder]/[deleted] are NOT stored on the row; the repository resolves them from the live `users`
 * rows in one batched query (#496/#505/#518) and hands them here, so from this entity's perspective they
 * are just two more raw, already-resolved booleans.
 */
data class SeedingEntryEntity(
    val seed: Int?,
    val position: Int,
    val userId: UUID?,
    val displayName: String?,
    val publicCode: String,
    val ntrpBand: String?,
    val rating: String,
    val sex: String?,
    val age: Int?,
    val placeholder: Boolean,
    val deleted: Boolean,
)
