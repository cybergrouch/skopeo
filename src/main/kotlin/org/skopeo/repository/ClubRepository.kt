// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

@file:Suppress("TooManyFunctions") // The #633 entity split adds the entity-graph readers to a cohesive repository.

package org.skopeo.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.model.CreateClubCommand
import org.skopeo.persistence.ClubAggregateEntity
import org.skopeo.persistence.ClubEntity
import java.util.UUID

/**
 * Persistence for clubs and their owners (#313). A club has zero or more owners (one row per
 * (club, user) in club_owners); [addOwner] is idempotent on that pair. Returns the raw
 * [ClubAggregateEntity] graph (#633); the service converts to the domain `Club` via `mapper.entity`.
 */
class ClubRepository {
    fun create(command: CreateClubCommand): ClubAggregateEntity =
        transaction {
            val id =
                ClubsTable.insertAndGetId {
                    it[name] = command.name
                    it[publicCode] = PublicCode.generate { code -> ClubsTable.selectAll().where { ClubsTable.publicCode eq code }.any() }
                    it[createdBy] = command.createdBy
                }.value
            ClubsTable.selectAll().where { ClubsTable.id eq id }.single().toClubAggregate()
        }

    fun findById(id: UUID): ClubAggregateEntity? =
        transaction { ClubsTable.selectAll().where { ClubsTable.id eq id }.singleOrNull()?.toClubAggregate() }

    /**
     * Resolve a club by its shareable public code (#327) for the public-by-code page. Unlike [list]
     * this does NOT filter on is_active — a soft-deleted club's link stays honored for traceability,
     * and the caller flags it (mirrors events/matches).
     */
    fun findByPublicCode(code: String): ClubAggregateEntity? =
        transaction { ClubsTable.selectAll().where { ClubsTable.publicCode eq code }.singleOrNull()?.toClubAggregate() }

    /** All active clubs, alphabetical by name. */
    fun list(): List<ClubAggregateEntity> =
        transaction {
            ClubsTable
                .selectAll()
                .where { ClubsTable.isActive eq true }
                .orderBy(ClubsTable.name to SortOrder.ASC)
                .map { it.toClubAggregate() }
        }

    /** Rename [id] (#325). Returns the refreshed club, or null if no such club. */
    fun rename(
        id: UUID,
        name: String,
    ): ClubAggregateEntity? =
        transaction {
            ClubsTable.selectAll().where { ClubsTable.id eq id }.singleOrNull() ?: return@transaction null
            ClubsTable.update(where = { ClubsTable.id eq id }) { it[ClubsTable.name] = name }
            ClubsTable.selectAll().where { ClubsTable.id eq id }.single().toClubAggregate()
        }

    /**
     * Soft-delete [id] (#325): flip is_active to false, mirroring how users and events are retired
     * rather than hard-deleted. Returns true if an active club was disabled (false if missing or
     * already disabled). The row and its event associations are kept for history; [list] hides it.
     */
    fun disable(id: UUID): Boolean =
        transaction {
            ClubsTable.update(where = { (ClubsTable.id eq id) and (ClubsTable.isActive eq true) }) { it[isActive] = false } > 0
        }

    /** Add [userId] as an owner of [clubId] (idempotent). Returns the refreshed club, or null if no such club. */
    fun addOwner(
        clubId: UUID,
        userId: UUID,
    ): ClubAggregateEntity? =
        transaction {
            val club = ClubsTable.selectAll().where { ClubsTable.id eq clubId }.singleOrNull() ?: return@transaction null
            val already =
                ClubOwnersTable
                    .selectAll()
                    .where { (ClubOwnersTable.clubId eq clubId) and (ClubOwnersTable.userId eq userId) }
                    .any()
            if (!already) {
                ClubOwnersTable.insert {
                    it[ClubOwnersTable.clubId] = clubId
                    it[ClubOwnersTable.userId] = userId
                }
            }
            club.toClubAggregate()
        }

    /** Remove [userId] as an owner of [clubId] (no-op if absent). Returns the refreshed club, or null if no such club. */
    fun removeOwner(
        clubId: UUID,
        userId: UUID,
    ): ClubAggregateEntity? =
        transaction {
            val club = ClubsTable.selectAll().where { ClubsTable.id eq clubId }.singleOrNull() ?: return@transaction null
            ClubOwnersTable.deleteWhere { (ClubOwnersTable.clubId eq clubId) and (ClubOwnersTable.userId eq userId) }
            club.toClubAggregate()
        }

    /** Set whether this club's tournaments are sanctioned (#525). Returns the refreshed club, or null if missing. */
    fun setSanction(
        id: UUID,
        sanctioned: Boolean,
    ): ClubAggregateEntity? =
        transaction {
            ClubsTable.selectAll().where { ClubsTable.id eq id }.singleOrNull() ?: return@transaction null
            ClubsTable.update(where = { ClubsTable.id eq id }) { it[tournamentsSanctioned] = sanctioned }
            ClubsTable.selectAll().where { ClubsTable.id eq id }.single().toClubAggregate()
        }

    /**
     * Read a clubs row plus its owner ids into the raw [ClubAggregateEntity] graph (#633), loading the
     * owners (runs in the caller's transaction). No domain construction — that is `mapper.entity`'s job.
     */
    private fun ResultRow.toClubAggregate(): ClubAggregateEntity {
        val entity = toClubEntity()
        val ownerIds =
            ClubOwnersTable.selectAll().where { ClubOwnersTable.clubId eq entity.id }.map { it[ClubOwnersTable.userId].value }
        return ClubAggregateEntity(club = entity, ownerIds = ownerIds)
    }

    /** Read only the raw `clubs`-row scalars into the persistence entity (#633); no child rows. */
    private fun ResultRow.toClubEntity(): ClubEntity =
        ClubEntity(
            id = this[ClubsTable.id].value,
            name = this[ClubsTable.name],
            publicCode = this[ClubsTable.publicCode],
            isActive = this[ClubsTable.isActive],
            tournamentsSanctioned = this[ClubsTable.tournamentsSanctioned],
            createdBy = this[ClubsTable.createdBy]?.value,
        )
}
