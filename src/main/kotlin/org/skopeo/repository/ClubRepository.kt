// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

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
import org.skopeo.model.Club
import org.skopeo.model.CreateClubCommand
import java.util.UUID

/**
 * Persistence for clubs and their owners (#313). A club has zero or more owners (one row per
 * (club, user) in club_owners); [addOwner] is idempotent on that pair.
 */
class ClubRepository {
    fun create(command: CreateClubCommand): Club =
        transaction {
            val id =
                ClubsTable.insertAndGetId {
                    it[name] = command.name
                    it[publicCode] = PublicCode.generate { code -> ClubsTable.selectAll().where { ClubsTable.publicCode eq code }.any() }
                    it[createdBy] = command.createdBy
                }.value
            ClubsTable.selectAll().where { ClubsTable.id eq id }.single().toClub()
        }

    fun findById(id: UUID): Club? = transaction { ClubsTable.selectAll().where { ClubsTable.id eq id }.singleOrNull()?.toClub() }

    /**
     * Resolve a club by its shareable public code (#327) for the public-by-code page. Unlike [list]
     * this does NOT filter on is_active — a soft-deleted club's link stays honored for traceability,
     * and the caller flags it (mirrors events/matches).
     */
    fun findByPublicCode(code: String): Club? =
        transaction { ClubsTable.selectAll().where { ClubsTable.publicCode eq code }.singleOrNull()?.toClub() }

    /** All active clubs, alphabetical by name. */
    fun list(): List<Club> =
        transaction {
            ClubsTable.selectAll().where { ClubsTable.isActive eq true }.orderBy(ClubsTable.name to SortOrder.ASC).map { it.toClub() }
        }

    /** Rename [id] (#325). Returns the refreshed club, or null if no such club. */
    fun rename(
        id: UUID,
        name: String,
    ): Club? =
        transaction {
            ClubsTable.selectAll().where { ClubsTable.id eq id }.singleOrNull() ?: return@transaction null
            ClubsTable.update(where = { ClubsTable.id eq id }) { it[ClubsTable.name] = name }
            ClubsTable.selectAll().where { ClubsTable.id eq id }.single().toClub()
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
    ): Club? =
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
            club.toClub()
        }

    /** Remove [userId] as an owner of [clubId] (no-op if absent). Returns the refreshed club, or null if no such club. */
    fun removeOwner(
        clubId: UUID,
        userId: UUID,
    ): Club? =
        transaction {
            val club = ClubsTable.selectAll().where { ClubsTable.id eq clubId }.singleOrNull() ?: return@transaction null
            ClubOwnersTable.deleteWhere { (ClubOwnersTable.clubId eq clubId) and (ClubOwnersTable.userId eq userId) }
            club.toClub()
        }

    /** Set whether this club's tournaments are sanctioned (#525). Returns the refreshed club, or null if missing. */
    fun setSanction(
        id: UUID,
        sanctioned: Boolean,
    ): Club? =
        transaction {
            ClubsTable.selectAll().where { ClubsTable.id eq id }.singleOrNull() ?: return@transaction null
            ClubsTable.update(where = { ClubsTable.id eq id }) { it[tournamentsSanctioned] = sanctioned }
            ClubsTable.selectAll().where { ClubsTable.id eq id }.single().toClub()
        }

    /** Map a clubs row to the domain, loading its owner ids (runs in the caller's transaction). */
    private fun ResultRow.toClub(): Club {
        val clubId = this[ClubsTable.id].value
        val ownerIds =
            ClubOwnersTable.selectAll().where { ClubOwnersTable.clubId eq clubId }.map { it[ClubOwnersTable.userId].value }
        return Club(
            id = clubId,
            name = this[ClubsTable.name],
            publicCode = this[ClubsTable.publicCode],
            isActive = this[ClubsTable.isActive],
            tournamentsSanctioned = this[ClubsTable.tournamentsSanctioned],
            createdBy = this[ClubsTable.createdBy]?.value,
            ownerIds = ownerIds,
        )
    }
}
