// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.model.Capability
import org.skopeo.model.CapabilityGrant
import java.time.LocalDateTime
import java.util.UUID

/**
 * Append-only persistence for capability (role) grants. A grant is an active row; revoking
 * flips it inactive with audit stamps; re-granting inserts a fresh active row. The DB enforces
 * at most one active grant per (user, capability).
 */
class CapabilityRepository {
    fun listByUser(userId: UUID): List<CapabilityGrant> =
        transaction {
            UserCapabilitiesTable
                .selectAll()
                .where { UserCapabilitiesTable.userId eq userId }
                .map { it.toCapabilityGrant() }
        }

    fun findActive(
        userId: UUID,
        capability: Capability,
    ): CapabilityGrant? =
        transaction {
            activeRow(userId = userId, capability = capability)?.toCapabilityGrant()
        }

    fun grant(
        userId: UUID,
        capability: Capability,
        // Null = a system grant (e.g. the verified-email bootstrap allowlist), not an admin action.
        grantedBy: UUID? = null,
    ): CapabilityGrant =
        transaction {
            val id =
                UserCapabilitiesTable.insertAndGetId {
                    it[UserCapabilitiesTable.userId] = userId
                    it[UserCapabilitiesTable.capability] = capability.name
                    it[UserCapabilitiesTable.grantedBy] = grantedBy
                    it[grantedAt] = LocalDateTime.now()
                }
            loadById(id = id.value)
        }

    /** Revoke the active grant (if any) — disables it with audit stamps. */
    fun revoke(
        userId: UUID,
        capability: Capability,
        revokedBy: UUID,
        revokedAt: LocalDateTime,
    ): CapabilityGrant? =
        transaction {
            val active = activeRow(userId = userId, capability = capability) ?: return@transaction null
            val id = active[UserCapabilitiesTable.id].value
            UserCapabilitiesTable.update(where = { UserCapabilitiesTable.id eq id }) {
                it[isActive] = false
                it[UserCapabilitiesTable.revokedAt] = revokedAt
                it[UserCapabilitiesTable.revokedBy] = revokedBy
            }
            loadById(id = id)
        }

    /** Count of users currently holding an active ADMINISTRATOR grant. */
    fun countActiveAdministrators(): Long =
        transaction {
            UserCapabilitiesTable
                .selectAll()
                .where {
                    (UserCapabilitiesTable.capability eq Capability.ADMINISTRATOR.name) and UserCapabilitiesTable.isActive
                }.count()
        }

    private fun activeRow(
        userId: UUID,
        capability: Capability,
    ): ResultRow? =
        UserCapabilitiesTable
            .selectAll()
            .where {
                (UserCapabilitiesTable.userId eq userId) and
                    (UserCapabilitiesTable.capability eq capability.name) and
                    UserCapabilitiesTable.isActive
            }.singleOrNull()

    private fun loadById(id: UUID): CapabilityGrant =
        UserCapabilitiesTable
            .selectAll()
            .where { UserCapabilitiesTable.id eq id }
            .single()
            .toCapabilityGrant()
}

internal fun ResultRow.toCapabilityGrant(): CapabilityGrant =
    CapabilityGrant(
        id = this[UserCapabilitiesTable.id].value,
        userId = this[UserCapabilitiesTable.userId].value,
        capability = Capability.valueOf(value = this[UserCapabilitiesTable.capability]),
        isActive = this[UserCapabilitiesTable.isActive],
        grantedBy = this[UserCapabilitiesTable.grantedBy]?.value,
        grantedAt = this[UserCapabilitiesTable.grantedAt],
        revokedBy = this[UserCapabilitiesTable.revokedBy]?.value,
        revokedAt = this[UserCapabilitiesTable.revokedAt],
    )
