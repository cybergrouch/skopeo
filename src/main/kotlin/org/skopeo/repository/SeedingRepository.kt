// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.skopeo.model.Seeding
import org.skopeo.model.SeedingEntry
import org.skopeo.model.ServiceError
import java.time.LocalDateTime
import java.util.UUID

/** Persistence for generated seeding snapshots (issue #111); one current seeding per list. */
class SeedingRepository {
    /** Replace any existing seeding for [listId] with a fresh snapshot (regenerate overwrites). */
    fun replace(
        listId: UUID,
        generatedBy: UUID?,
        entries: List<SeedingEntry>,
    ): Seeding =
        transaction {
            SeedingsTable.deleteWhere { SeedingsTable.listId eq listId } // cascades existing entries
            val now = LocalDateTime.now()
            val seedingId =
                SeedingsTable.insertAndGetId {
                    it[SeedingsTable.listId] = listId
                    it[generatedAt] = now
                    it[SeedingsTable.generatedBy] = generatedBy
                }.value
            entries.forEach { entry ->
                SeedingEntriesTable.insert {
                    it[SeedingEntriesTable.seedingId] = seedingId
                    it[seed] = entry.seed
                    it[position] = entry.position
                    it[SeedingEntriesTable.userId] = entry.userId
                    it[displayName] = entry.displayName
                    it[publicCode] = entry.publicCode
                    it[ntrpBand] = entry.ntrpBand
                    it[rating] = entry.rating
                    it[sex] = entry.sex
                    it[age] = entry.age
                }
            }
            Seeding(id = seedingId, listId = listId, generatedAt = now, entries = entries)
        }

    fun findByListId(listId: UUID): Either<ServiceError, Seeding> =
        transaction {
            val row = SeedingsTable.selectAll().where { SeedingsTable.listId eq listId }.singleOrNull()
            if (row == null) {
                ServiceError.NotFound(message = "No seeding for list $listId").left()
            } else {
                val id = row[SeedingsTable.id].value
                val rows =
                    SeedingEntriesTable
                        .selectAll()
                        .where { SeedingEntriesTable.seedingId eq id }
                        .orderBy(SeedingEntriesTable.position to SortOrder.ASC)
                        .toList()
                // The snapshot table doesn't persist the placeholder flag (#496/#505); resolve it from the
                // live user rows in ONE batched query keyed by user id (never per-row). A claimed placeholder
                // is re-pointed to the claimant (#496), so a stored user id always resolves to a current user.
                val placeholderById = placeholderByUserId(userIds = rows.mapNotNull { it[SeedingEntriesTable.userId]?.value })
                val entries = rows.map { it.toSeedingEntry(placeholderById = placeholderById) }
                Seeding(id = id, listId = listId, generatedAt = row[SeedingsTable.generatedAt], entries = entries).right()
            }
        }

    /** Batched user id → placeholder flag lookup (#496/#505) for a page of seeding rows; empty ids → empty map. */
    private fun placeholderByUserId(userIds: List<UUID>): Map<UUID, Boolean> =
        if (userIds.isEmpty()) {
            emptyMap()
        } else {
            UsersTable
                .select(columns = listOf(UsersTable.id, UsersTable.placeholder))
                .where { UsersTable.id inList userIds.distinct() }
                .associate { it[UsersTable.id].value to it[UsersTable.placeholder] }
        }

    private fun ResultRow.toSeedingEntry(placeholderById: Map<UUID, Boolean>): SeedingEntry {
        val userId = this[SeedingEntriesTable.userId]?.value
        return SeedingEntry(
            seed = this[SeedingEntriesTable.seed],
            position = this[SeedingEntriesTable.position],
            userId = userId,
            displayName = this[SeedingEntriesTable.displayName],
            publicCode = this[SeedingEntriesTable.publicCode],
            ntrpBand = this[SeedingEntriesTable.ntrpBand],
            rating = this[SeedingEntriesTable.rating],
            sex = this[SeedingEntriesTable.sex],
            age = this[SeedingEntriesTable.age],
            placeholder = userId?.let { placeholderById[it] } ?: false,
        )
    }
}
