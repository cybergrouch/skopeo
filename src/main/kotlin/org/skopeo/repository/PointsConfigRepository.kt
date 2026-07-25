// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID

/** A single points_config row (#552/#553): the stored JSON value plus who last wrote it and when. */
data class PointsConfigRow(
    val key: String,
    val value: String,
    val updatedBy: UUID?,
    val updatedAt: LocalDateTime,
)

/**
 * Persistence for the points_config JSON store (#552/#553). [get] reads one schedule by key; [upsert]
 * writes it (insert-or-update on the primary key), recording the admin and time.
 */
class PointsConfigRepository {
    fun get(key: String): PointsConfigRow? =
        transaction { PointsConfigTable.selectAll().where { PointsConfigTable.key eq key }.singleOrNull()?.toRow() }

    fun upsert(
        key: String,
        value: String,
        updatedBy: UUID,
    ): PointsConfigRow =
        transaction {
            val now = LocalDateTime.now()
            val exists = PointsConfigTable.selectAll().where { PointsConfigTable.key eq key }.any()
            if (exists) {
                PointsConfigTable.update(where = { PointsConfigTable.key eq key }) {
                    it[PointsConfigTable.value] = value
                    it[PointsConfigTable.updatedBy] = updatedBy
                    it[PointsConfigTable.updatedAt] = now
                }
            } else {
                PointsConfigTable.insert {
                    it[PointsConfigTable.key] = key
                    it[PointsConfigTable.value] = value
                    it[PointsConfigTable.updatedBy] = updatedBy
                    it[PointsConfigTable.updatedAt] = now
                }
            }
            PointsConfigTable.selectAll().where { PointsConfigTable.key eq key }.single().toRow()
        }

    private fun ResultRow.toRow(): PointsConfigRow =
        PointsConfigRow(
            key = this[PointsConfigTable.key],
            value = this[PointsConfigTable.value],
            updatedBy = this[PointsConfigTable.updatedBy]?.value,
            updatedAt = this[PointsConfigTable.updatedAt],
        )
}
