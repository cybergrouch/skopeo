// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import arrow.core.Either
import arrow.core.flatten
import arrow.core.left
import arrow.core.right
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.model.Name
import org.skopeo.model.NameType
import org.skopeo.model.ServiceError
import java.time.LocalDateTime
import java.util.UUID

/** Name types of which a user holds at most one active value; adding a new one supersedes the old. */
private val SINGLE_VALUED_NAME_TYPES = setOf(NameType.DISPLAY, NameType.FIRST, NameType.LAST)

/**
 * Row-level persistence for individual [Name]s. Names are append-only: created here, never
 * edited; a name is retired by disabling it ([setActive]). A user may hold many active
 * names; the single-valued types (DISPLAY/FIRST/LAST) keep exactly one active — adding a new
 * one atomically disables the previous one of that type.
 */
class NameRepository {
    fun listByUser(userId: UUID): List<Name> =
        transaction {
            UserNamesTable
                .selectAll()
                .where { UserNamesTable.userId eq userId }
                .map { it.toName() }
        }

    fun findById(id: UUID): Either<ServiceError, Name> =
        transaction {
            val row = UserNamesTable.selectAll().where { UserNamesTable.id eq id }.singleOrNull()
            if (row == null) ServiceError.NotFound(message = "Name $id not found").left() else row.toName().right()
        }

    /**
     * Add a name. For the single-valued types ([SINGLE_VALUED_NAME_TYPES] — DISPLAY/FIRST/LAST) the
     * current active name of that type is disabled first, so exactly one stays active and the new
     * value supersedes the old (which is retained as history).
     */
    fun create(
        userId: UUID,
        type: NameType,
        value: String,
    ): Name =
        transaction {
            if (type in SINGLE_VALUED_NAME_TYPES) disableActiveOfType(userId = userId, type = type)
            val id =
                UserNamesTable.insertAndGetId {
                    it[UserNamesTable.userId] = userId
                    it[nameType] = type.name
                    it[UserNamesTable.value] = value
                }
            loadById(id = id.value)
        }

    /**
     * Enable/disable a name. Re-enabling a former DISPLAY name violates the single-active-display unique
     * index — surfaced as a [ServiceError.Conflict]; a missing row is a [ServiceError.NotFound].
     */
    fun setActive(
        id: UUID,
        active: Boolean,
        disabledAt: LocalDateTime?,
    ): Either<ServiceError, Name> =
        conflictAware(message = "A different active display name already exists") {
            transaction {
                val updated =
                    UserNamesTable.update(where = { UserNamesTable.id eq id }) {
                        it[isActive] = active
                        it[UserNamesTable.disabledAt] = disabledAt
                    }
                if (updated == 0) ServiceError.NotFound(message = "Name $id not found").left() else loadById(id = id).right()
            }
        }.flatten()

    private fun disableActiveOfType(
        userId: UUID,
        type: NameType,
    ) {
        UserNamesTable.update(
            where = {
                (UserNamesTable.userId eq userId) and
                    UserNamesTable.isActive and
                    (UserNamesTable.nameType eq type.name)
            },
        ) {
            it[isActive] = false
            it[disabledAt] = LocalDateTime.now()
        }
    }

    private fun loadById(id: UUID): Name =
        UserNamesTable
            .selectAll()
            .where { UserNamesTable.id eq id }
            .single()
            .toName()
}

/** Map a user_names row to the [Name] domain type. Shared with [UserRepository]. */
internal fun ResultRow.toName(): Name =
    Name(
        id = this[UserNamesTable.id].value,
        userId = this[UserNamesTable.userId].value,
        type = NameType.valueOf(value = this[UserNamesTable.nameType]),
        value = this[UserNamesTable.value],
        isActive = this[UserNamesTable.isActive],
        disabledAt = this[UserNamesTable.disabledAt],
    )
