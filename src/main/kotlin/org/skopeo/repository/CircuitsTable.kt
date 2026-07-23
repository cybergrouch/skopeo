// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.datetime

private const val CIRCUIT_NAME_MAX = 120

/**
 * Exposed mapping over the V25 circuits table (#525). created_at is DB-managed (default), so it is
 * not set on insert. Mirrors [ClubsTable]: soft-delete via is_active, created_by nullable to match
 * ON DELETE SET NULL.
 */
internal object CircuitsTable : UUIDTable(name = "circuits") {
    val name = varchar(name = "name", length = CIRCUIT_NAME_MAX)
    val isActive = bool(name = "is_active").default(defaultValue = true)
    val createdBy = reference(name = "created_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val createdAt = datetime(name = "created_at")
}
