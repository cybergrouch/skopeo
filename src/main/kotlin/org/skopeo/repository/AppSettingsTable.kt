// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

private const val SETTING_KEY_MAX = 64

/**
 * Exposed mapping over the V11 app_settings key-value table (#378). A generic global-settings store
 * ('ui_theme', the AUTO schedule 'ui_theme_schedule' and the feature flags); [updatedBy]/[updatedAt] track
 * who last wrote the value for the audit
 * surface. updated_at is DB-defaulted but set explicitly on every write.
 */
internal object AppSettingsTable : Table(name = "app_settings") {
    val key = varchar(name = "key", length = SETTING_KEY_MAX)
    override val primaryKey = PrimaryKey(firstColumn = key)

    // TEXT since V68 (#1196): the AUTO theme schedule is a JSON list that outgrew VARCHAR(64).
    val value = text(name = "value")
    val updatedBy = reference(name = "updated_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val updatedAt = datetime(name = "updated_at")
}
