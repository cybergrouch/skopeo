// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

// Money-style scale-4 columns. The tie-break rating is NUMERIC(6,4) (an NTRP rating <= 7.0000); the
// ordering value is widened to NUMERIC(12,4) (V14) so it can hold either a rating or an unbounded points
// total (#146). Flyway (V12 + V14) owns the DDL; this maps only what the repository touches.
private const val ORDERING_PRECISION = 12
private const val TIEBREAK_PRECISION = 6
private const val ORDERING_SCALE = 4
private const val BAND_CODE_MAX = 8
private const val SEX_MAX = 16
private const val STATUS_MAX = 16
private const val SOURCE_MAX = 8

/** A standings-snapshot generation (#220): a computation run whose entries reads serve when PUBLISHED. */
internal object StandingsSnapshotsTable : UUIDTable(name = "standings_snapshots") {
    // DB-assigned monotonic insertion order; reads pick the latest PUBLISHED generation by this, not by
    // computed_at (which can collide when two rebuilds land in the same clock tick).
    val seq = long(name = "seq").databaseGenerated()
    val computedAt = datetime(name = "computed_at")
    val asOf = date(name = "as_of")
    val status = varchar(name = "status", length = STATUS_MAX)

    // What produced the generation (#146): RATING (phase-1 rating-derived) or POINTS (ledger recompute).
    // Flyway V14 owns the DDL (DEFAULT 'RATING' backfills the pre-#146 rows); reads prefer POINTS.
    // Named `sourceCol` because `source` collides with Exposed's ColumnSet.source member.
    val sourceCol = varchar(name = "source", length = SOURCE_MAX)
}

/** The pre-ranked rows of a snapshot (#220), one per (band, sex, rank). Source-agnostic ordering value. */
internal object StandingsEntriesTable : UUIDTable(name = "standings_entries") {
    val snapshotId = reference(name = "snapshot_id", foreign = StandingsSnapshotsTable, onDelete = ReferenceOption.CASCADE)
    val band = varchar(name = "band", length = BAND_CODE_MAX)
    val sex = varchar(name = "sex", length = SEX_MAX)
    val rank = integer(name = "rank")
    val userId = reference(name = "user_id", foreign = UsersTable, onDelete = ReferenceOption.CASCADE)
    val orderingValue = decimal(name = "ordering_value", precision = ORDERING_PRECISION, scale = ORDERING_SCALE)
    val tiebreakRating = decimal(name = "tiebreak_rating", precision = TIEBREAK_PRECISION, scale = ORDERING_SCALE).nullable()
    val achievedAt = datetime(name = "achieved_at").nullable()
}
