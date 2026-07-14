// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.datetime

private const val CLUB_NAME_MAX = 120

/**
 * Exposed mappings over the V5 clubs / club_owners tables (#313). created_at is DB-managed
 * (default), so it is not set on insert.
 */
internal object ClubsTable : UUIDTable(name = "clubs") {
    val name = varchar(name = "name", length = CLUB_NAME_MAX)
    val isActive = bool(name = "is_active").default(defaultValue = true)
    val createdBy = reference(name = "created_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val createdAt = datetime(name = "created_at")
}

internal object ClubOwnersTable : UUIDTable(name = "club_owners") {
    val clubId = reference(name = "club_id", foreign = ClubsTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference(name = "user_id", foreign = UsersTable, onDelete = ReferenceOption.CASCADE)
    val createdAt = datetime(name = "created_at")
}
