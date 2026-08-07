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
import org.skopeo.common.error.ServiceError
import org.skopeo.domain.model.Seeding
import org.skopeo.domain.model.SeedingEntry
import org.skopeo.repository.persistence.SeedingAggregateEntity
import org.skopeo.repository.persistence.SeedingEntity
import org.skopeo.repository.persistence.SeedingEntryEntity
import java.time.LocalDateTime
import java.util.UUID

/**
 * Persistence for generated seeding snapshots (issue #111, extended by #714); one current seeding per
 * source. A [SeedingSource] discriminator selects the source column — a player list or an event — so
 * the entry-insert and read logic is shared and both sources produce identical output.
 */
class SeedingRepository {
    /** The source a seeding belongs to (#714): exactly one column is set on the `seedings` row. */
    sealed interface SeedingSource {
        data class List(val listId: UUID) : SeedingSource

        data class Event(val eventId: UUID) : SeedingSource
    }

    /** Replace any existing seeding for [listId] with a fresh snapshot (regenerate overwrites). */
    fun replace(
        listId: UUID,
        generatedBy: UUID?,
        entries: List<SeedingEntry>,
    ): Seeding = replaceForSource(source = SeedingSource.List(listId = listId), generatedBy = generatedBy, entries = entries)

    /** Replace any existing seeding for [eventId] with a fresh snapshot (#714, regenerate overwrites). */
    fun replaceForEvent(
        eventId: UUID,
        generatedBy: UUID?,
        entries: List<SeedingEntry>,
    ): Seeding = replaceForSource(source = SeedingSource.Event(eventId = eventId), generatedBy = generatedBy, entries = entries)

    fun findByListId(listId: UUID): Either<ServiceError, SeedingAggregateEntity> =
        findForSource(source = SeedingSource.List(listId = listId))

    fun findByEventId(eventId: UUID): Either<ServiceError, SeedingAggregateEntity> =
        findForSource(source = SeedingSource.Event(eventId = eventId))

    /** Delete any existing seeding for the source, then insert the fresh snapshot in one transaction. */
    private fun replaceForSource(
        source: SeedingSource,
        generatedBy: UUID?,
        entries: List<SeedingEntry>,
    ): Seeding =
        transaction {
            // Delete-by-source cascades the existing entries; a nullable source column keeps FK integrity.
            when (source) {
                is SeedingSource.List -> SeedingsTable.deleteWhere { listId eq source.listId }
                is SeedingSource.Event -> SeedingsTable.deleteWhere { eventId eq source.eventId }
            }
            val now = LocalDateTime.now()
            val sourceListId = (source as? SeedingSource.List)?.listId
            val sourceEventId = (source as? SeedingSource.Event)?.eventId
            val newSeedingId =
                SeedingsTable.insertAndGetId {
                    it[listId] = sourceListId
                    it[eventId] = sourceEventId
                    it[generatedAt] = now
                    it[SeedingsTable.generatedBy] = generatedBy
                }.value
            entries.forEach { entry ->
                SeedingEntriesTable.insert {
                    it[seedingId] = newSeedingId
                    it[seed] = entry.seed
                    it[position] = entry.position
                    it[userId] = entry.userId
                    it[displayName] = entry.displayName
                    it[publicCode] = entry.publicCode
                    it[ntrpBand] = entry.ntrpBand
                    it[rating] = entry.rating
                    it[sex] = entry.sex
                    it[age] = entry.age
                }
            }
            Seeding(id = newSeedingId, listId = sourceListId, eventId = sourceEventId, generatedAt = now, entries = entries)
        }

    private fun findForSource(source: SeedingSource): Either<ServiceError, SeedingAggregateEntity> =
        transaction {
            val row =
                when (source) {
                    is SeedingSource.List -> SeedingsTable.selectAll().where { SeedingsTable.listId eq source.listId }
                    is SeedingSource.Event -> SeedingsTable.selectAll().where { SeedingsTable.eventId eq source.eventId }
                }.singleOrNull()
            if (row == null) {
                ServiceError.NotFound(message = "No seeding for $source").left()
            } else {
                val id = row[SeedingsTable.id].value
                val rows =
                    SeedingEntriesTable
                        .selectAll()
                        .where { SeedingEntriesTable.seedingId eq id }
                        .orderBy(SeedingEntriesTable.position to SortOrder.ASC)
                        .toList()
                // The snapshot table doesn't persist the placeholder/deleted flags (#496/#505/#518); resolve
                // them from the live user rows in ONE batched query keyed by user id (never per-row). A claimed
                // placeholder is re-pointed to the claimant (#496), so a stored user id always resolves.
                val statusById = statusByUserId(userIds = rows.mapNotNull { it[SeedingEntriesTable.userId]?.value })
                val entries = rows.map { it.toSeedingEntryEntity(statusById = statusById) }
                SeedingAggregateEntity(seeding = row.toSeedingEntity(), entries = entries).right()
            }
        }

    /** The live placeholder/deleted flags for a snapshotted seeding user (#496/#505/#518), resolved at read. */
    private data class UserStatus(
        val placeholder: Boolean,
        val deleted: Boolean,
    )

    /** Batched user id → live status lookup for a page of seeding rows; empty ids → empty map. */
    private fun statusByUserId(userIds: List<UUID>): Map<UUID, UserStatus> =
        if (userIds.isEmpty()) {
            emptyMap()
        } else {
            UsersTable
                .select(columns = listOf(UsersTable.id, UsersTable.placeholder, UsersTable.isActive, UsersTable.canonicalUserId))
                .where { UsersTable.id inList userIds.distinct() }
                .associate {
                    it[UsersTable.id].value to
                        UserStatus(
                            placeholder = it[UsersTable.placeholder],
                            // "deleted" = inactive AND no canonical pointer (#518) — a merged duplicate is not deleted.
                            deleted = !it[UsersTable.isActive] && it[UsersTable.canonicalUserId] == null,
                        )
                }
        }

    private fun ResultRow.toSeedingEntryEntity(statusById: Map<UUID, UserStatus>): SeedingEntryEntity {
        val userId = this[SeedingEntriesTable.userId]?.value
        val status = userId?.let { statusById[it] }
        return SeedingEntryEntity(
            seed = this[SeedingEntriesTable.seed],
            position = this[SeedingEntriesTable.position],
            userId = userId,
            displayName = this[SeedingEntriesTable.displayName],
            publicCode = this[SeedingEntriesTable.publicCode],
            ntrpBand = this[SeedingEntriesTable.ntrpBand],
            rating = this[SeedingEntriesTable.rating],
            sex = this[SeedingEntriesTable.sex],
            age = this[SeedingEntriesTable.age],
            placeholder = status?.placeholder ?: false,
            deleted = status?.deleted ?: false,
        )
    }

    private fun ResultRow.toSeedingEntity(): SeedingEntity =
        SeedingEntity(
            id = this[SeedingsTable.id].value,
            listId = this[SeedingsTable.listId]?.value,
            eventId = this[SeedingsTable.eventId]?.value,
            generatedAt = this[SeedingsTable.generatedAt],
            generatedBy = this[SeedingsTable.generatedBy]?.value,
        )
}
