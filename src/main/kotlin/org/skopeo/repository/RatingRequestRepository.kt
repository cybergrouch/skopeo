// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import arrow.core.Either
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.model.RatingRequest
import org.skopeo.model.RatingRequestStatus
import org.skopeo.model.ServiceError
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/** Persistence for re-rate requests (#140). The partial unique index enforces one open per player. */
class RatingRequestRepository {
    /** Create a PENDING request; the open-per-player unique index surfaces as a [ServiceError.Conflict]. */
    fun create(
        userId: UUID,
        justification: String,
    ): Either<ServiceError, RatingRequest> =
        conflictAware(message = "You already have an open re-rate request") {
            transaction {
                val id =
                    RatingRequestsTable.insertAndGetId {
                        it[RatingRequestsTable.userId] = userId
                        it[RatingRequestsTable.justification] = justification
                        it[status] = RatingRequestStatus.PENDING.name
                        it[createdAt] = LocalDateTime.now()
                    }.value
                loadById(id = id)
            }
        }

    fun findById(id: UUID): RatingRequest? =
        transaction { RatingRequestsTable.selectAll().where { RatingRequestsTable.id eq id }.singleOrNull()?.toRatingRequest() }

    /** The player's most recent request (any status) — what the Profile tab shows. */
    fun findLatestByUser(userId: UUID): RatingRequest? =
        transaction {
            RatingRequestsTable
                .selectAll()
                .where { RatingRequestsTable.userId eq userId }
                .orderBy(RatingRequestsTable.createdAt to SortOrder.DESC)
                .limit(n = 1)
                .singleOrNull()
                ?.toRatingRequest()
        }

    /** A page of requests, newest first, optionally filtered by [status]. */
    fun list(
        limit: Int,
        offset: Int,
        status: RatingRequestStatus?,
    ): Pair<List<RatingRequest>, Long> =
        transaction {
            val base = RatingRequestsTable.selectAll()
            val filtered = if (status != null) base.where { RatingRequestsTable.status eq status.name } else base
            val total = filtered.count()
            val items =
                filtered
                    .orderBy(RatingRequestsTable.createdAt to SortOrder.DESC)
                    .limit(n = limit, offset = offset.toLong())
                    .map { it.toRatingRequest() }
            items to total
        }

    /** Mark a PENDING request resolved (APPROVED/DENIED); returns the updated request, or null if absent/not pending. */
    fun resolve(
        id: UUID,
        status: RatingRequestStatus,
        newRating: BigDecimal?,
        reason: String?,
        resolvedBy: UUID,
    ): RatingRequest? =
        transaction {
            val updated =
                RatingRequestsTable.update(
                    where = { (RatingRequestsTable.id eq id) and (RatingRequestsTable.status eq RatingRequestStatus.PENDING.name) },
                ) {
                    it[RatingRequestsTable.status] = status.name
                    it[RatingRequestsTable.newRating] = newRating
                    it[RatingRequestsTable.reason] = reason
                    it[RatingRequestsTable.resolvedBy] = resolvedBy
                    it[resolvedAt] = LocalDateTime.now()
                }
            if (updated == 0) null else loadById(id = id)
        }

    private fun loadById(id: UUID): RatingRequest =
        RatingRequestsTable.selectAll().where { RatingRequestsTable.id eq id }.single().toRatingRequest()
}

internal fun ResultRow.toRatingRequest(): RatingRequest =
    RatingRequest(
        id = this[RatingRequestsTable.id].value,
        userId = this[RatingRequestsTable.userId].value,
        justification = this[RatingRequestsTable.justification],
        status = RatingRequestStatus.valueOf(value = this[RatingRequestsTable.status]),
        newRating = this[RatingRequestsTable.newRating],
        reason = this[RatingRequestsTable.reason],
        resolvedBy = this[RatingRequestsTable.resolvedBy]?.value,
        resolvedAt = this[RatingRequestsTable.resolvedAt],
        createdAt = this[RatingRequestsTable.createdAt],
    )
