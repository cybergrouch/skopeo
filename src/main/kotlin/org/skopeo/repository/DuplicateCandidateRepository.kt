// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.model.DuplicateCandidate
import org.skopeo.model.DuplicateCandidateStatus
import org.skopeo.model.DuplicateSignal
import org.skopeo.model.ServiceError
import java.time.LocalDateTime
import java.util.UUID

/**
 * Persistence for duplicate-account candidates (issue #126). The suspected pair is stored ordered so the
 * same two accounts collapse to one row; [flag] is idempotent per pair while a candidate stays OPEN.
 */
class DuplicateCandidateRepository {
    /**
     * Raise (or return the existing) OPEN candidate for the unordered pair [userAId]/[userBId]. Returns
     * the existing OPEN candidate unchanged if one already exists — never stacks duplicates.
     */
    fun flag(
        userAId: UUID,
        userBId: UUID,
        signal: DuplicateSignal,
        detail: String?,
        flaggedBy: UUID?,
    ): DuplicateCandidate =
        transaction {
            val (a, b) = orderedPair(first = userAId, second = userBId)
            openRow(a = a, b = b)?.let { return@transaction it.toCandidate() }
            val flaggedAt = LocalDateTime.now()
            val id =
                DuplicateCandidatesTable.insertAndGetId {
                    it[DuplicateCandidatesTable.userAId] = a
                    it[DuplicateCandidatesTable.userBId] = b
                    it[DuplicateCandidatesTable.signal] = signal.name
                    it[DuplicateCandidatesTable.detail] = detail
                    it[DuplicateCandidatesTable.flaggedBy] = flaggedBy
                    it[DuplicateCandidatesTable.flaggedAt] = flaggedAt
                }.value
            DuplicateCandidate(
                id = id,
                userAId = a,
                userBId = b,
                signal = signal,
                detail = detail,
                status = DuplicateCandidateStatus.OPEN,
                flaggedBy = flaggedBy,
                flaggedAt = flaggedAt,
                resolvedBy = null,
                resolvedAt = null,
            )
        }

    /** One page of candidates (newest first) plus the total; optionally scoped to a [status]. */
    fun list(
        limit: Int,
        offset: Int,
        status: DuplicateCandidateStatus?,
    ): Pair<List<DuplicateCandidate>, Long> =
        transaction {
            fun query() =
                if (status == null) {
                    DuplicateCandidatesTable.selectAll()
                } else {
                    DuplicateCandidatesTable.selectAll().where { DuplicateCandidatesTable.status eq status.name }
                }
            val total = query().count()
            val items =
                query()
                    .orderBy(DuplicateCandidatesTable.flaggedAt to SortOrder.DESC)
                    .limit(n = limit, offset = offset.toLong())
                    .map { it.toCandidate() }
            items to total
        }

    fun findById(id: UUID): Either<ServiceError, DuplicateCandidate> =
        transaction {
            val candidate = loadById(id = id)
            if (candidate == null) ServiceError.NotFound(message = "No duplicate candidate $id").left() else candidate.right()
        }

    /**
     * Transition a candidate's status, recording who/when resolved it (null for a dismissal). A missing
     * row is a [ServiceError.NotFound].
     */
    fun setStatus(
        id: UUID,
        status: DuplicateCandidateStatus,
        resolvedBy: UUID?,
        resolvedAt: LocalDateTime?,
    ): Either<ServiceError, DuplicateCandidate> =
        transaction {
            val updated =
                DuplicateCandidatesTable.update(where = { DuplicateCandidatesTable.id eq id }) {
                    it[DuplicateCandidatesTable.status] = status.name
                    it[DuplicateCandidatesTable.resolvedBy] = resolvedBy
                    it[DuplicateCandidatesTable.resolvedAt] = resolvedAt
                }
            if (updated == 0) {
                ServiceError.NotFound(message = "No duplicate candidate $id").left()
            } else {
                loadByIdOrThrow(id = id).right()
            }
        }

    private fun orderedPair(
        first: UUID,
        second: UUID,
    ): Pair<UUID, UUID> = if (first < second) first to second else second to first

    private fun openRow(
        a: UUID,
        b: UUID,
    ): ResultRow? =
        DuplicateCandidatesTable
            .selectAll()
            .where {
                (DuplicateCandidatesTable.userAId eq a) and
                    (DuplicateCandidatesTable.userBId eq b) and
                    (DuplicateCandidatesTable.status eq DuplicateCandidateStatus.OPEN.name)
            }.firstOrNull()

    private fun loadById(id: UUID): DuplicateCandidate? =
        DuplicateCandidatesTable.selectAll().where { DuplicateCandidatesTable.id eq id }.map { it.toCandidate() }.firstOrNull()

    private fun loadByIdOrThrow(id: UUID): DuplicateCandidate =
        DuplicateCandidatesTable.selectAll().where { DuplicateCandidatesTable.id eq id }.single().toCandidate()
}

internal fun ResultRow.toCandidate(): DuplicateCandidate =
    DuplicateCandidate(
        id = this[DuplicateCandidatesTable.id].value,
        userAId = this[DuplicateCandidatesTable.userAId].value,
        userBId = this[DuplicateCandidatesTable.userBId].value,
        signal = DuplicateSignal.valueOf(value = this[DuplicateCandidatesTable.signal]),
        detail = this[DuplicateCandidatesTable.detail],
        status = DuplicateCandidateStatus.valueOf(value = this[DuplicateCandidatesTable.status]),
        flaggedBy = this[DuplicateCandidatesTable.flaggedBy]?.value,
        flaggedAt = this[DuplicateCandidatesTable.flaggedAt],
        resolvedBy = this[DuplicateCandidatesTable.resolvedBy]?.value,
        resolvedAt = this[DuplicateCandidatesTable.resolvedAt],
    )
