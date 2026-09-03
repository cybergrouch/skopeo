// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Exposed mapping over the V47 points_schedule_versions table (#862): one row per points-schedule
 * version, append-only.
 *
 * [isCurrent] is deliberately **not** named `isActive`. Elsewhere in this schema `is_active` means "not
 * soft-deleted" and many rows are active at once; here exactly one row may be current, enforced by the
 * partial unique index `uq_points_schedule_current` rather than by service code.
 */
internal object PointsScheduleVersionsTable : Table(name = "points_schedule_versions") {
    val version = integer(name = "version")
    override val primaryKey = PrimaryKey(firstColumn = version)
    val isCurrent = bool(name = "is_current")
    val createdBy = reference(name = "created_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val createdAt = datetime(name = "created_at")
}
