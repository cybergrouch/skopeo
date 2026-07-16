// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

// The ordering value + tie-break rating are money-style NUMERIC(6,4): NTRP fits (7.0000) and points
// (#146) fit the same width. Flyway (V12) owns the DDL; this maps only what the repository touches.
private const val ORDERING_PRECISION = 6
private const val ORDERING_SCALE = 4
private const val BAND_CODE_MAX = 8
private const val SEX_MAX = 16
private const val STATUS_MAX = 16

/** A standings-snapshot generation (#220): a computation run whose entries reads serve when PUBLISHED. */
internal object StandingsSnapshotsTable : UUIDTable(name = "standings_snapshots") {
    // DB-assigned monotonic insertion order; reads pick the latest PUBLISHED generation by this, not by
    // computed_at (which can collide when two rebuilds land in the same clock tick).
    val seq = long(name = "seq").databaseGenerated()
    val computedAt = datetime(name = "computed_at")
    val asOf = date(name = "as_of")
    val status = varchar(name = "status", length = STATUS_MAX)
}

/** The pre-ranked rows of a snapshot (#220), one per (band, sex, rank). Source-agnostic ordering value. */
internal object StandingsEntriesTable : UUIDTable(name = "standings_entries") {
    val snapshotId = reference(name = "snapshot_id", foreign = StandingsSnapshotsTable, onDelete = ReferenceOption.CASCADE)
    val band = varchar(name = "band", length = BAND_CODE_MAX)
    val sex = varchar(name = "sex", length = SEX_MAX)
    val rank = integer(name = "rank")
    val userId = reference(name = "user_id", foreign = UsersTable, onDelete = ReferenceOption.CASCADE)
    val orderingValue = decimal(name = "ordering_value", precision = ORDERING_PRECISION, scale = ORDERING_SCALE)
    val tiebreakRating = decimal(name = "tiebreak_rating", precision = ORDERING_PRECISION, scale = ORDERING_SCALE).nullable()
    val achievedAt = datetime(name = "achieved_at").nullable()
}
