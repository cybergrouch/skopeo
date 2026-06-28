// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.datetime

private const val LIST_NAME_MAX = 120
private const val DISPLAY_NAME_MAX = 255
private const val PUBLIC_CODE_MAX = 16
private const val BAND_MAX = 8
private const val RATING_MAX = 32
private const val SEX_MAX = 16

/** Exposed mappings over the V8 host-seeding tables (issue #111). */
internal object PlayerListsTable : UUIDTable(name = "player_lists") {
    val ownerId = reference(name = "owner_id", foreign = UsersTable, onDelete = ReferenceOption.CASCADE)
    val name = varchar(name = "name", length = LIST_NAME_MAX)
    val createdAt = datetime(name = "created_at")
}

internal object PlayerListMembersTable : UUIDTable(name = "player_list_members") {
    val listId = reference(name = "list_id", foreign = PlayerListsTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference(name = "user_id", foreign = UsersTable, onDelete = ReferenceOption.CASCADE)
    val addedAt = datetime(name = "added_at")
}

/** One current seeding per list (regenerate overwrites) — enforced by a unique index on list_id. */
internal object SeedingsTable : UUIDTable(name = "seedings") {
    val listId = reference(name = "list_id", foreign = PlayerListsTable, onDelete = ReferenceOption.CASCADE)
    val generatedAt = datetime(name = "generated_at")
    val generatedBy = reference(name = "generated_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
}

/** A frozen seeding row — names/ratings captured at generation so the CSV is reproducible. */
internal object SeedingEntriesTable : UUIDTable(name = "seeding_entries") {
    val seedingId = reference(name = "seeding_id", foreign = SeedingsTable, onDelete = ReferenceOption.CASCADE)
    val seed = integer(name = "seed").nullable()
    val position = integer(name = "position")
    val userId = reference(name = "user_id", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val displayName = varchar(name = "display_name", length = DISPLAY_NAME_MAX).nullable()
    val publicCode = varchar(name = "public_code", length = PUBLIC_CODE_MAX)
    val ntrpBand = varchar(name = "ntrp_band", length = BAND_MAX).nullable()
    val rating = varchar(name = "rating", length = RATING_MAX)
    val sex = varchar(name = "sex", length = SEX_MAX).nullable()
    val age = integer(name = "age").nullable()
}
