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

/** A single app_settings row (#378): the stored value plus who last wrote it and when. */
data class AppSettingRow(
    val key: String,
    val value: String,
    val updatedBy: UUID?,
    val updatedAt: LocalDateTime,
)

/**
 * Persistence for the generic app_settings key-value store (#378). [get] reads one setting by key;
 * [upsert] writes it (insert-or-update on the primary key), recording the admin and time.
 */
class AppSettingsRepository {
    fun get(key: String): AppSettingRow? =
        transaction { AppSettingsTable.selectAll().where { AppSettingsTable.key eq key }.singleOrNull()?.toRow() }

    fun upsert(
        key: String,
        value: String,
        updatedBy: UUID,
    ): AppSettingRow =
        transaction {
            val now = LocalDateTime.now()
            val exists = AppSettingsTable.selectAll().where { AppSettingsTable.key eq key }.any()
            if (exists) {
                AppSettingsTable.update(where = { AppSettingsTable.key eq key }) {
                    it[AppSettingsTable.value] = value
                    it[AppSettingsTable.updatedBy] = updatedBy
                    it[AppSettingsTable.updatedAt] = now
                }
            } else {
                AppSettingsTable.insert {
                    it[AppSettingsTable.key] = key
                    it[AppSettingsTable.value] = value
                    it[AppSettingsTable.updatedBy] = updatedBy
                    it[AppSettingsTable.updatedAt] = now
                }
            }
            AppSettingsTable.selectAll().where { AppSettingsTable.key eq key }.single().toRow()
        }

    private fun ResultRow.toRow(): AppSettingRow =
        AppSettingRow(
            key = this[AppSettingsTable.key],
            value = this[AppSettingsTable.value],
            updatedBy = this[AppSettingsTable.updatedBy]?.value,
            updatedAt = this[AppSettingsTable.updatedAt],
        )
}
