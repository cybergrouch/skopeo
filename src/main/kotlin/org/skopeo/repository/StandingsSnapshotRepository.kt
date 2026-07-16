// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.skopeo.model.SnapshotStatus
import org.skopeo.model.StandingsBand
import org.skopeo.model.StandingsEntryWrite
import org.skopeo.model.StandingsLocation
import org.skopeo.model.StandingsPage
import org.skopeo.model.StandingsSnapshotEntry
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The sentinel persisted in [StandingsEntriesTable.sex] for the "Unspecified" group — the column is
 * NOT NULL (a (band, sex) group is always identified), so a null sex round-trips through this code.
 */
internal const val UNSPECIFIED_SEX = "Unspecified"

/**
 * Persistence for the paged standings snapshot (#220). Reads serve the latest PUBLISHED generation:
 * [create] writes a whole generation (the snapshot + its pre-ranked entries) in one transaction,
 * [latestPublished] finds the generation reads target, and [page]/[locate] serve it in SQL.
 */
class StandingsSnapshotRepository {
    /**
     * Persist a whole generation in one transaction: insert the snapshot header, then bulk-insert its
     * pre-ranked [entries]. Returns the new snapshot id. Existing generations are kept (history).
     */
    fun create(
        computedAt: LocalDateTime,
        asOf: LocalDate,
        status: SnapshotStatus,
        entries: List<StandingsEntryWrite>,
    ): UUID =
        transaction {
            val snapshotId =
                StandingsSnapshotsTable.insert {
                    it[StandingsSnapshotsTable.computedAt] = computedAt
                    it[StandingsSnapshotsTable.asOf] = asOf
                    it[StandingsSnapshotsTable.status] = status.name
                }[StandingsSnapshotsTable.id].value
            StandingsEntriesTable.batchInsert(data = entries) { entry ->
                this[StandingsEntriesTable.snapshotId] = snapshotId
                this[StandingsEntriesTable.band] = entry.band.code
                this[StandingsEntriesTable.sex] = entry.sex ?: UNSPECIFIED_SEX
                this[StandingsEntriesTable.rank] = entry.rank
                this[StandingsEntriesTable.userId] = entry.userId
                this[StandingsEntriesTable.orderingValue] = entry.orderingValue
                this[StandingsEntriesTable.tiebreakRating] = entry.tiebreakRating
                this[StandingsEntriesTable.achievedAt] = entry.achievedAt
            }
            snapshotId
        }

    /** The id of the newest PUBLISHED snapshot (by computed_at), or null when none has been built yet. */
    fun latestPublished(): UUID? =
        transaction {
            StandingsSnapshotsTable
                .selectAll()
                .where { StandingsSnapshotsTable.status eq SnapshotStatus.PUBLISHED.name }
                .orderBy(StandingsSnapshotsTable.seq to SortOrder.DESC)
                .limit(n = 1)
                .map { it[StandingsSnapshotsTable.id].value }
                .firstOrNull()
        }

    /** All (band, sex) groups present in [snapshotId], in strongest-band, then Men→Women→Unspecified order. */
    fun groups(snapshotId: UUID): List<Pair<StandingsBand, String?>> =
        transaction {
            StandingsEntriesTable
                .select(columns = listOf(StandingsEntriesTable.band, StandingsEntriesTable.sex))
                .where { StandingsEntriesTable.snapshotId eq snapshotId }
                .withDistinct()
                .map { row ->
                    StandingsBand.requireCode(code = row[StandingsEntriesTable.band]) to row.sexValue()
                }.sortedWith(comparator = groupComparator())
        }

    /** One page of a (band, sex) group in [snapshotId], ordered by rank, plus the group's total size. */
    fun page(
        snapshotId: UUID,
        band: StandingsBand,
        sex: String?,
        limit: Int,
        offset: Int,
    ): StandingsPage =
        transaction {
            val query = { groupWhere(snapshotId = snapshotId, band = band, sex = sex) }
            val total = query().count()
            val entries =
                query()
                    .orderBy(StandingsEntriesTable.rank to SortOrder.ASC)
                    .limit(n = limit, offset = offset.toLong())
                    .map { it.toSnapshotEntry(band = band) }
            StandingsPage(entries = entries, total = total.toInt())
        }

    /** Where [userId] sits in [snapshotId] — their (band, sex, rank) — or null if absent (e.g. unrated). */
    fun locate(
        snapshotId: UUID,
        userId: UUID,
    ): StandingsLocation? =
        transaction {
            StandingsEntriesTable
                .selectAll()
                .where { (StandingsEntriesTable.snapshotId eq snapshotId) and (StandingsEntriesTable.userId eq userId) }
                .firstOrNull()
                ?.let { row ->
                    val band = StandingsBand.requireCode(code = row[StandingsEntriesTable.band])
                    StandingsLocation(band = band, sex = row.sexValue(), rank = row[StandingsEntriesTable.rank])
                }
        }

    private fun groupWhere(
        snapshotId: UUID,
        band: StandingsBand,
        sex: String?,
    ) = StandingsEntriesTable
        .selectAll()
        .where {
            (StandingsEntriesTable.snapshotId eq snapshotId) and
                (StandingsEntriesTable.band eq band.code) and
                (StandingsEntriesTable.sex eq (sex ?: UNSPECIFIED_SEX))
        }

    /** Strongest band first (enum ordinal descending), then Men → Women → Unspecified — mirrors the read UI. */
    private fun groupComparator(): Comparator<Pair<StandingsBand, String?>> =
        compareByDescending<Pair<StandingsBand, String?>> { it.first.ordinal }.thenBy { sexOrder(sex = it.second) }

    private fun sexOrder(sex: String?): Int =
        when (sex) {
            "Male" -> 0
            "Female" -> 1
            else -> 2
        }

    private fun ResultRow.sexValue(): String? = this[StandingsEntriesTable.sex].let { if (it == UNSPECIFIED_SEX) null else it }

    // [band] is the group filtered on, so every row carries it — no need to re-parse the persisted code.
    private fun ResultRow.toSnapshotEntry(band: StandingsBand): StandingsSnapshotEntry =
        StandingsSnapshotEntry(
            band = band,
            sex = sexValue(),
            rank = this[StandingsEntriesTable.rank],
            userId = this[StandingsEntriesTable.userId].value,
            orderingValue = this[StandingsEntriesTable.orderingValue],
        )
}
