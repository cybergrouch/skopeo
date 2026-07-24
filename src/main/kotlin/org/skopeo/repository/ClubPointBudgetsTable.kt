// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

private const val EVENT_TYPE_MAX = 16

/**
 * Exposed mapping over the V16 club_point_budgets table (#403 Phase B): a club's per-type budget
 * allocation. The composite PK (club_id, event_type) means at most one budget per (club, type);
 * [updatedBy]/[updatedAt] track who last wrote it for the audit surface.
 */
internal object ClubPointBudgetsTable : Table(name = "club_point_budgets") {
    val clubId = reference(name = "club_id", foreign = ClubsTable, onDelete = ReferenceOption.CASCADE)
    val eventType = varchar(name = "event_type", length = EVENT_TYPE_MAX)
    val budgetedPoints = integer(name = "budgeted_points").default(defaultValue = 0)
    val updatedBy = reference(name = "updated_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val updatedAt = datetime(name = "updated_at")
    override val primaryKey = PrimaryKey(firstColumn = clubId, eventType)
}
