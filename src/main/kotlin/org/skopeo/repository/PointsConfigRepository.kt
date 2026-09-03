// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.repository.persistence.PointsConfigEntity
import java.time.LocalDateTime
import java.util.UUID

/**
 * Persistence for the versioned points_config JSON store (#552/#553, versioned in #862). [get] reads one
 * schedule as of a version (the current one by default); [appendVersion] records an edit as a new version.
 * There is no update path — the store is append-only, which is what keeps an old award's rates
 * retrievable. Returns the raw [PointsConfigEntity] (#633); the service decodes `value`.
 */
class PointsConfigRepository {
    /**
     * The version new awards are computed under (#862) — the single row with `is_current`.
     *
     * Not nullable: V47 seeds v1 and the partial unique index guarantees exactly one current row, so an
     * absent one means the database was not migrated. Failing loudly beats silently awarding under an
     * invented version.
     */
    fun currentVersion(): Int =
        transaction {
            PointsScheduleVersionsTable
                .select(columns = listOf(element = PointsScheduleVersionsTable.version))
                .where { PointsScheduleVersionsTable.isCurrent eq true }
                .singleOrNull()
                ?.get(expression = PointsScheduleVersionsTable.version)
                ?: error(message = "No current points-schedule version — points_schedule_versions is unseeded (V47).")
        }

    /** One schedule document as of [version]; defaults to the current version. */
    fun get(
        key: String,
        version: Int = currentVersion(),
    ): PointsConfigEntity? =
        transaction {
            PointsConfigTable
                .selectAll()
                .where { (PointsConfigTable.key eq key) and (PointsConfigTable.version eq version) }
                .singleOrNull()
                ?.toRow()
        }

    /**
     * Record an edited schedule as a **new version** (#862), returning the stored row.
     *
     * Replaces the old `upsert`, which overwrote the previous document and so destroyed the only record of
     * what earlier awards had been paid under. Every schedule moves forward together: the other keys are
     * copied verbatim from the outgoing version, because one global version spanning all three schedules is
     * what makes "which document set applied" a single answerable question.
     *
     * The current pointer is moved by clearing the outgoing row **before** inserting the incoming one —
     * `uq_points_schedule_current` is a unique index, so the two-current state must never exist even
     * momentarily within the transaction.
     */
    fun appendVersion(
        key: String,
        value: String,
        // Named actorId, not updatedBy: inside Exposed's insert lambda the table is the receiver, so a
        // parameter sharing a column's name is shadowed by that column.
        actorId: UUID,
    ): PointsConfigEntity =
        transaction {
            val now = LocalDateTime.now()
            val outgoing = currentVersion()
            val nextVersion =
                (
                    PointsScheduleVersionsTable
                        .select(columns = listOf(element = PointsScheduleVersionsTable.version))
                        .maxOfOrNull { it[PointsScheduleVersionsTable.version] } ?: 0
                ) + 1

            // Carry every key forward, with the edited one replaced. A key absent from the outgoing version
            // (possible only if a future key is added mid-life) simply does not appear in the new one.
            val carried =
                PointsConfigTable
                    .selectAll()
                    .where { PointsConfigTable.version eq outgoing }
                    .associate { it[PointsConfigTable.key] to it[PointsConfigTable.value] }
                    .toMutableMap()
            carried[key] = value

            PointsScheduleVersionsTable.update(where = { PointsScheduleVersionsTable.isCurrent eq true }) {
                it[isCurrent] = false
            }
            PointsScheduleVersionsTable.insert {
                it[version] = nextVersion
                it[isCurrent] = true
                it[createdBy] = actorId
                it[createdAt] = now
            }
            carried.forEach { (carriedKey, carriedValue) ->
                PointsConfigTable.insert {
                    it[version] = nextVersion
                    it[PointsConfigTable.key] = carriedKey
                    it[PointsConfigTable.value] = carriedValue
                    it[updatedBy] = actorId
                    it[updatedAt] = now
                }
            }
            PointsConfigTable
                .selectAll()
                .where { (PointsConfigTable.key eq key) and (PointsConfigTable.version eq nextVersion) }
                .single()
                .toRow()
        }

    private fun ResultRow.toRow(): PointsConfigEntity =
        PointsConfigEntity(
            key = this[PointsConfigTable.key],
            value = this[PointsConfigTable.value],
            updatedBy = this[PointsConfigTable.updatedBy]?.value,
            updatedAt = this[PointsConfigTable.updatedAt],
        )
}
