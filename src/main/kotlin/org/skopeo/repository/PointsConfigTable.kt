// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

private const val CONFIG_KEY_MAX = 64

/**
 * Exposed mapping over the V28 points_config table (#552/#553): each admin-configurable points
 * schedule stored as a JSON document under a stable key. [updatedBy]/[updatedAt] track provenance for
 * the audit surface. updated_at is DB-defaulted but set explicitly on every write.
 */
internal object PointsConfigTable : Table(name = "points_config") {
    val key = varchar(name = "key", length = CONFIG_KEY_MAX)
    override val primaryKey = PrimaryKey(firstColumn = key)
    val value = text(name = "value")
    val updatedBy = reference(name = "updated_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val updatedAt = datetime(name = "updated_at")
}
