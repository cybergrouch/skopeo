// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

private const val SETTING_KEY_MAX = 64
private const val SETTING_VALUE_MAX = 64

/**
 * Exposed mapping over the V11 app_settings key-value table (#378). A generic global-settings store
 * (currently only 'ui_theme'); [updatedBy]/[updatedAt] track who last wrote the value for the audit
 * surface. updated_at is DB-defaulted but set explicitly on every write.
 */
internal object AppSettingsTable : Table(name = "app_settings") {
    val key = varchar(name = "key", length = SETTING_KEY_MAX)
    override val primaryKey = PrimaryKey(firstColumn = key)
    val value = varchar(name = "value", length = SETTING_VALUE_MAX)
    val updatedBy = reference(name = "updated_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val updatedAt = datetime(name = "updated_at")
}
