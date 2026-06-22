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
 * names; the display name is the single active name of type DISPLAY — adding a new DISPLAY
 * name atomically disables the previous one, so exactly one stays active.
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

    /** Add a name. Adding a DISPLAY name disables the current active display name first. */
    fun create(
        userId: UUID,
        type: NameType,
        value: String,
    ): Name =
        transaction {
            if (type == NameType.DISPLAY) disableActiveDisplay(userId)
            val id =
                UserNamesTable.insertAndGetId {
                    it[UserNamesTable.userId] = userId
                    it[nameType] = type.name
                    it[UserNamesTable.value] = value
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

    private fun disableActiveDisplay(userId: UUID) {
        UserNamesTable.update(
            {
                (UserNamesTable.userId eq userId) and
                    UserNamesTable.isActive and
                    (UserNamesTable.nameType eq NameType.DISPLAY.name)
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
        type = NameType.valueOf(this[UserNamesTable.nameType]),
        value = this[UserNamesTable.value],
        isActive = this[UserNamesTable.isActive],
        disabledAt = this[UserNamesTable.disabledAt],
    )
