// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.model.CreateCircuitCommand
import org.skopeo.repository.persistence.CircuitEntity
import java.util.UUID

/**
 * Persistence for circuits (#525). Mirrors [ClubRepository] minus the owner association: create,
 * read, rename, and soft-delete (is_active). created_at is DB-managed. Returns raw [CircuitEntity]
 * rows (#633); the service converts to the domain `Circuit` via `mapper.entity`.
 */
class CircuitRepository {
    fun create(command: CreateCircuitCommand): CircuitEntity =
        transaction {
            val id =
                CircuitsTable.insertAndGetId {
                    it[name] = command.name
                    it[createdBy] = command.createdBy
                }.value
            CircuitsTable.selectAll().where { CircuitsTable.id eq id }.single().toCircuitEntity()
        }

    fun findById(id: UUID): CircuitEntity? =
        transaction { CircuitsTable.selectAll().where { CircuitsTable.id eq id }.singleOrNull()?.toCircuitEntity() }

    /** All active circuits, alphabetical by name. */
    fun list(): List<CircuitEntity> =
        transaction {
            CircuitsTable
                .selectAll()
                .where { CircuitsTable.isActive eq true }
                .orderBy(CircuitsTable.name to SortOrder.ASC)
                .map { it.toCircuitEntity() }
        }

    /** Rename [id] (#525). Returns the refreshed circuit, or null if no such circuit. */
    fun rename(
        id: UUID,
        name: String,
    ): CircuitEntity? =
        transaction {
            CircuitsTable.selectAll().where { CircuitsTable.id eq id }.singleOrNull() ?: return@transaction null
            CircuitsTable.update(where = { CircuitsTable.id eq id }) { it[CircuitsTable.name] = name }
            CircuitsTable.selectAll().where { CircuitsTable.id eq id }.single().toCircuitEntity()
        }

    /**
     * Soft-delete [id] (#525): flip is_active to false, mirroring clubs. Returns true if an active
     * circuit was disabled (false if missing or already disabled).
     */
    fun disable(id: UUID): Boolean =
        transaction {
            CircuitsTable.update(
                where = { (CircuitsTable.id eq id) and (CircuitsTable.isActive eq true) },
            ) { it[isActive] = false } > 0
        }

    /** Map a `circuits` row to the raw persistence entity (#633) — no derivations, no child rows. */
    private fun ResultRow.toCircuitEntity(): CircuitEntity =
        CircuitEntity(
            id = this[CircuitsTable.id].value,
            name = this[CircuitsTable.name],
            isActive = this[CircuitsTable.isActive],
            createdBy = this[CircuitsTable.createdBy]?.value,
        )
}
