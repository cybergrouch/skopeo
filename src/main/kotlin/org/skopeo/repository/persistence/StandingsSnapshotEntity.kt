// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of a standings-snapshot generation's own row (#975/#974) — the header, without
 * its entries.
 *
 * [asOf] and [computedAt] are **not** the same thing and both matter to a reader: `asOf` is the date
 * the standings *describe* (which decides which awards had expired), `computedAt` is when the run
 * actually happened. They diverge whenever a run is late or is re-run for an earlier date, which is
 * exactly the case worth being able to see. Kept model-free so `persistence` stays a leaf.
 */
data class StandingsSnapshotEntity(
    val id: UUID,
    val computedAt: LocalDateTime,
    val asOf: LocalDate,
    val status: String,
    val source: String,
)
