// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of a `standings_entries` row (#220/#633): the dumb, as-stored data with **no
 * behaviour**. Standings is a projection store (there is no single aggregate root), so this entity is the
 * one repeated row shape reads consume. Held as RAW stored values — [band] is the stored band *code* and
 * [sex] the stored group label (including the `"Unspecified"` sentinel), both interpreted at the
 * conversion boundary into the domain `StandingsSnapshotEntry` (`StandingsBand` and the null-sex mapping),
 * since `persistence` is a leaf that must not import `model`. Includes the write-side [tiebreakRating] and
 * [achievedAt] columns for faithfulness even though the read projection doesn't surface them.
 */
data class StandingsEntryEntity(
    val snapshotId: UUID,
    val band: String,
    val sex: String,
    val rank: Int,
    val userId: UUID,
    val orderingValue: BigDecimal,
    val tiebreakRating: BigDecimal?,
    val achievedAt: LocalDateTime?,
)
