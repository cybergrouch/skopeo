// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.skopeo.model.PlayerList
import org.skopeo.model.ServiceError
import java.time.LocalDateTime
import java.util.UUID

/** Persistence for host-curated player lists and their members (issue #111). */
class PlayerListRepository {
    fun create(
        ownerId: UUID,
        name: String,
    ): PlayerList =
        transaction {
            val now = LocalDateTime.now()
            val id =
                PlayerListsTable.insertAndGetId {
                    it[PlayerListsTable.ownerId] = ownerId
                    it[PlayerListsTable.name] = name
                    it[createdAt] = now
                }.value
            PlayerList(id = id, ownerId = ownerId, name = name, createdAt = now, memberUserIds = emptyList())
        }

    /** The owner's lists, newest first, each with its member ids. */
    fun listByOwner(ownerId: UUID): List<PlayerList> =
        transaction {
            PlayerListsTable
                .selectAll()
                .where { PlayerListsTable.ownerId eq ownerId }
                .orderBy(PlayerListsTable.createdAt to SortOrder.DESC)
                .map { it.toPlayerList(memberIds = memberIdsOf(listId = it[PlayerListsTable.id].value)) }
        }

    fun findById(id: UUID): Either<ServiceError, PlayerList> =
        transaction {
            val row = PlayerListsTable.selectAll().where { PlayerListsTable.id eq id }.singleOrNull()
            if (row == null) {
                ServiceError.NotFound(message = "Player list $id not found").left()
            } else {
                row.toPlayerList(memberIds = memberIdsOf(listId = id)).right()
            }
        }

    fun delete(id: UUID): Either<ServiceError, Unit> =
        transaction {
            val deleted = PlayerListsTable.deleteWhere { PlayerListsTable.id eq id }
            if (deleted == 0) ServiceError.NotFound(message = "Player list $id not found").left() else Unit.right()
        }

    /** Add a member; a duplicate (already in the list) surfaces as a [ServiceError.Conflict]. */
    fun addMember(
        listId: UUID,
        userId: UUID,
    ): Either<ServiceError, Unit> =
        conflictAware(message = "That player is already in the list") {
            transaction {
                PlayerListMembersTable.insert {
                    it[PlayerListMembersTable.listId] = listId
                    it[PlayerListMembersTable.userId] = userId
                    it[addedAt] = LocalDateTime.now()
                }
                Unit
            }
        }

    fun removeMember(
        listId: UUID,
        userId: UUID,
    ): Either<ServiceError, Unit> =
        transaction {
            val deleted =
                PlayerListMembersTable.deleteWhere {
                    (PlayerListMembersTable.listId eq listId) and (PlayerListMembersTable.userId eq userId)
                }
            if (deleted == 0) ServiceError.NotFound(message = "Player $userId is not in list $listId").left() else Unit.right()
        }

    private fun memberIdsOf(listId: UUID): List<UUID> =
        PlayerListMembersTable
            .selectAll()
            .where { PlayerListMembersTable.listId eq listId }
            .orderBy(PlayerListMembersTable.addedAt to SortOrder.ASC)
            .map { it[PlayerListMembersTable.userId].value }

    private fun ResultRow.toPlayerList(memberIds: List<UUID>): PlayerList =
        PlayerList(
            id = this[PlayerListsTable.id].value,
            ownerId = this[PlayerListsTable.ownerId].value,
            name = this[PlayerListsTable.name],
            createdAt = this[PlayerListsTable.createdAt],
            memberUserIds = memberIds,
        )
}
