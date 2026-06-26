// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.model.Invite
import org.skopeo.model.InviteStatus
import java.time.LocalDateTime
import java.util.UUID

/**
 * Persistence for admin invites (issue #74). One PENDING invite per email at a time: re-inviting an
 * email with a PENDING invite rotates that row (new expiry) rather than stacking duplicates.
 */
class InviteRepository {
    /** Create a PENDING invite for [email], or rotate the existing PENDING one's expiry/inviter. */
    fun createOrRotate(
        email: String,
        invitedBy: UUID?,
        expiresAt: LocalDateTime,
    ): Invite =
        transaction {
            val existing = pendingRow(email = email)
            val id =
                if (existing == null) {
                    InvitesTable.insertAndGetId {
                        it[InvitesTable.email] = email
                        it[InvitesTable.invitedBy] = invitedBy
                        it[InvitesTable.expiresAt] = expiresAt
                    }.value
                } else {
                    val existingId = existing[InvitesTable.id].value
                    InvitesTable.update(where = { InvitesTable.id eq existingId }) {
                        it[InvitesTable.invitedBy] = invitedBy
                        it[InvitesTable.expiresAt] = expiresAt
                    }
                    existingId
                }
            loadById(id = id) ?: error(message = "Invite $id could not be read back")
        }

    /** The open (PENDING and unexpired) invite for [email], if any — the provisioning gate. */
    fun findOpenByEmail(
        email: String,
        asOf: LocalDateTime,
    ): Invite? =
        transaction {
            InvitesTable
                .selectAll()
                .where {
                    (InvitesTable.email eq email) and
                        (InvitesTable.status eq InviteStatus.PENDING.name) and
                        (InvitesTable.expiresAt greater asOf)
                }.map { it.toInvite() }
                .firstOrNull()
        }

    /** Mark the PENDING invite for [email] accepted (called once provisioning succeeds). */
    fun markAccepted(
        email: String,
        acceptedAt: LocalDateTime,
    ) {
        transaction {
            val row = pendingRow(email = email) ?: return@transaction
            InvitesTable.update(where = { InvitesTable.id eq row[InvitesTable.id].value }) {
                it[status] = InviteStatus.ACCEPTED.name
                it[InvitesTable.acceptedAt] = acceptedAt
            }
        }
    }

    /** Revoke an invite by id; returns the updated invite, or null if no such invite. */
    fun revoke(id: UUID): Invite? =
        transaction {
            val updated =
                InvitesTable.update(where = { InvitesTable.id eq id }) {
                    it[status] = InviteStatus.REVOKED.name
                }
            if (updated == 0) null else loadById(id = id)
        }

    /**
     * One page of invites (newest first) plus the total count. When [status] is given, only invites
     * with that stored status are returned (EXPIRED is not stored — it is a derived view of PENDING,
     * so `status = PENDING` covers both pending and expired invites). See issue #85.
     */
    fun list(
        limit: Int,
        offset: Int,
        status: InviteStatus? = null,
    ): Pair<List<Invite>, Long> =
        transaction {
            fun query() =
                if (status == null) {
                    InvitesTable.selectAll()
                } else {
                    InvitesTable.selectAll().where { InvitesTable.status eq status.name }
                }
            val total = query().count()
            val items =
                query()
                    .orderBy(InvitesTable.createdAt to SortOrder.DESC)
                    .limit(n = limit, offset = offset.toLong())
                    .map { it.toInvite() }
            items to total
        }

    private fun pendingRow(email: String): ResultRow? =
        InvitesTable
            .selectAll()
            .where { (InvitesTable.email eq email) and (InvitesTable.status eq InviteStatus.PENDING.name) }
            .firstOrNull()

    private fun loadById(id: UUID): Invite? = InvitesTable.selectAll().where { InvitesTable.id eq id }.map { it.toInvite() }.firstOrNull()
}

internal fun ResultRow.toInvite(): Invite =
    Invite(
        id = this[InvitesTable.id].value,
        email = this[InvitesTable.email],
        status = InviteStatus.valueOf(value = this[InvitesTable.status]),
        invitedBy = this[InvitesTable.invitedBy]?.value,
        expiresAt = this[InvitesTable.expiresAt],
        acceptedAt = this[InvitesTable.acceptedAt],
        createdAt = this[InvitesTable.createdAt],
    )
