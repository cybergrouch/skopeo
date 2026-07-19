// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.model.ClaimCode
import org.skopeo.model.ClaimCodeStatus
import java.time.LocalDateTime
import java.util.UUID

/**
 * Persistence for placeholder claim codes (#496). Codes are stored hashed (never plaintext). At most one
 * ACTIVE code exists per placeholder: [issue] supersedes any prior ACTIVE code (marks it CONSUMED) before
 * inserting the new one, in one transaction.
 */
class PlaceholderClaimCodeRepository {
    /**
     * Supersede any prior ACTIVE code for [placeholderUserId] (CONSUMED), then insert a new ACTIVE code
     * with the given [codeHash]/[expiresAt]. Returns the stored record. The plaintext is the caller's to
     * hand off; only its hash is persisted here.
     */
    fun issue(
        placeholderUserId: UUID,
        codeHash: String,
        expiresAt: LocalDateTime,
        createdBy: UUID?,
    ): ClaimCode =
        transaction {
            val now = LocalDateTime.now()
            PlaceholderClaimCodesTable.update(
                where = {
                    (PlaceholderClaimCodesTable.placeholderUserId eq placeholderUserId) and
                        (PlaceholderClaimCodesTable.status eq ClaimCodeStatus.ACTIVE.name)
                },
            ) {
                it[status] = ClaimCodeStatus.CONSUMED.name
                it[consumedAt] = now
            }
            val id =
                PlaceholderClaimCodesTable.insertAndGetId {
                    it[PlaceholderClaimCodesTable.placeholderUserId] = placeholderUserId
                    it[PlaceholderClaimCodesTable.codeHash] = codeHash
                    it[PlaceholderClaimCodesTable.expiresAt] = expiresAt
                    it[status] = ClaimCodeStatus.ACTIVE.name
                    it[PlaceholderClaimCodesTable.createdBy] = createdBy
                    it[createdAt] = now
                }
            loadByIdOrThrow(id = id.value)
        }

    /** The ACTIVE code matching [codeHash], if any (the claim lookup). Expiry is checked by the caller. */
    fun findActiveByHash(codeHash: String): ClaimCode? =
        transaction {
            PlaceholderClaimCodesTable
                .selectAll()
                .where {
                    (PlaceholderClaimCodesTable.codeHash eq codeHash) and
                        (PlaceholderClaimCodesTable.status eq ClaimCodeStatus.ACTIVE.name)
                }.firstOrNull()
                ?.toClaimCode()
        }

    /** Mark [id] CONSUMED by [consumedBy] at [consumedAt] — the single-use consumption on a successful claim. */
    fun consume(
        id: UUID,
        consumedBy: UUID,
        consumedAt: LocalDateTime,
    ) {
        transaction {
            PlaceholderClaimCodesTable.update(where = { PlaceholderClaimCodesTable.id eq id }) {
                it[status] = ClaimCodeStatus.CONSUMED.name
                it[PlaceholderClaimCodesTable.consumedBy] = consumedBy
                it[PlaceholderClaimCodesTable.consumedAt] = consumedAt
            }
        }
    }

    private fun loadByIdOrThrow(id: UUID): ClaimCode =
        PlaceholderClaimCodesTable.selectAll().where { PlaceholderClaimCodesTable.id eq id }.single().toClaimCode()
}

internal fun ResultRow.toClaimCode(): ClaimCode =
    ClaimCode(
        id = this[PlaceholderClaimCodesTable.id].value,
        placeholderUserId = this[PlaceholderClaimCodesTable.placeholderUserId].value,
        codeHash = this[PlaceholderClaimCodesTable.codeHash],
        expiresAt = this[PlaceholderClaimCodesTable.expiresAt],
        status = ClaimCodeStatus.valueOf(value = this[PlaceholderClaimCodesTable.status]),
        createdBy = this[PlaceholderClaimCodesTable.createdBy]?.value,
        createdAt = this[PlaceholderClaimCodesTable.createdAt],
        consumedAt = this[PlaceholderClaimCodesTable.consumedAt],
        consumedBy = this[PlaceholderClaimCodesTable.consumedBy]?.value,
    )
