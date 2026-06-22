package org.skopeo.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.model.Name
import org.skopeo.model.NameType
import java.time.LocalDateTime
import java.util.UUID

/**
 * Row-level persistence for individual [Name]s. Names are append-only: created here, never
 * edited; a name is retired by disabling it ([setActive]). A user may hold many active
 * names, with at most one active primary (the display name) enforced by the DB.
 */
class NameRepository {
    fun listByUser(userId: UUID): List<Name> =
        transaction {
            UserNamesTable
                .selectAll()
                .where { UserNamesTable.userId eq userId }
                .map { it.toName() }
        }

    fun findById(id: UUID): Name? =
        transaction {
            UserNamesTable
                .selectAll()
                .where { UserNamesTable.id eq id }
                .singleOrNull()
                ?.toName()
        }

    /** Add a name. When [primary] is set, the current active primary (if any) is demoted first. */
    fun create(
        userId: UUID,
        type: NameType,
        value: String,
        primary: Boolean,
    ): Name =
        transaction {
            if (primary) demoteActivePrimary(userId)
            val id =
                UserNamesTable.insertAndGetId {
                    it[UserNamesTable.userId] = userId
                    it[nameType] = type.name
                    it[UserNamesTable.value] = value
                    it[isPrimary] = primary
                }
            loadById(id.value)
        }

    fun setActive(
        id: UUID,
        active: Boolean,
        disabledAt: LocalDateTime?,
    ): Name? =
        transaction {
            val updated =
                UserNamesTable.update({ UserNamesTable.id eq id }) {
                    it[isActive] = active
                    it[UserNamesTable.disabledAt] = disabledAt
                }
            if (updated == 0) null else loadById(id)
        }

    /** Whether the user has any active name (used to auto-mark the first one primary). */
    fun hasActiveName(userId: UUID): Boolean =
        transaction {
            UserNamesTable
                .selectAll()
                .where { (UserNamesTable.userId eq userId) and UserNamesTable.isActive }
                .limit(1)
                .any()
        }

    private fun demoteActivePrimary(userId: UUID) {
        UserNamesTable.update(
            { (UserNamesTable.userId eq userId) and UserNamesTable.isActive and UserNamesTable.isPrimary },
        ) {
            it[isPrimary] = false
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
        type = NameType.valueOf(this[UserNamesTable.nameType]),
        value = this[UserNamesTable.value],
        isPrimary = this[UserNamesTable.isPrimary],
        isActive = this[UserNamesTable.isActive],
        disabledAt = this[UserNamesTable.disabledAt],
    )
