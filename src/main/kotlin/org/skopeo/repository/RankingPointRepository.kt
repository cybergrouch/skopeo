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
import org.skopeo.model.AwardStatus
import org.skopeo.model.PointClass
import org.skopeo.model.PointSourceType
import org.skopeo.model.RankingPointAward
import org.skopeo.model.RankingPointAwardWrite
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * Persistence for the append-only ranking-points ledger (#146). [award] inserts an ACTIVE row.
 * [revoke] implements the chosen revocation model: it flips the original row's status to REVOKED
 * *and* appends a REVOKED marker row (zero points, revokes_award_id → original) so the trail is
 * truthfully append-only while the as-of total stays a simple ACTIVE-in-window sum. Flyway owns DDL.
 */
class RankingPointRepository {
    /** Insert an award row from a fully-resolved [write]; returns the persisted award. */
    fun award(write: RankingPointAwardWrite): RankingPointAward =
        transaction {
            val id = insertRow(write = write)
            RankingPointAwardsTable.selectAll().where { RankingPointAwardsTable.id eq id }.single().toRankingPointAward()
        }

    fun findById(id: UUID): RankingPointAward? =
        transaction {
            RankingPointAwardsTable.selectAll().where { RankingPointAwardsTable.id eq id }.singleOrNull()?.toRankingPointAward()
        }

    /**
     * Revoke [awardId] (#146): flip the original ACTIVE row to REVOKED and append a REVOKED marker row
     * (zero points) referencing it. Returns the appended marker row, or null if the award is missing
     * or is already revoked. Idempotent: a second revoke of the same award is a no-op → null.
     */
    fun revoke(
        awardId: UUID,
        revokedBy: UUID?,
        reason: String?,
        revokedAt: LocalDateTime,
    ): RankingPointAward? =
        transaction {
            // Split the null-guard from the mapping so there's no chained safe-call+elvis arm that no
            // test can reach (both real outcomes — found / already-revoked — are covered).
            val originalRow =
                RankingPointAwardsTable
                    .selectAll()
                    .where { (RankingPointAwardsTable.id eq awardId) and (RankingPointAwardsTable.status eq AwardStatus.ACTIVE.name) }
                    .singleOrNull()
                    ?: return@transaction null
            val original = originalRow.toRankingPointAward()
            RankingPointAwardsTable.update(where = { RankingPointAwardsTable.id eq awardId }) {
                it[status] = AwardStatus.REVOKED.name
            }
            val markerId =
                insertRow(
                    write =
                        RankingPointAwardWrite(
                            userId = original.userId,
                            points = BigDecimal.ZERO,
                            pointClass = original.pointClass,
                            sourceType = original.sourceType,
                            sourceId = original.sourceId,
                            band = original.band,
                            sex = original.sex,
                            reason = reason,
                            validFrom = original.validFrom,
                            validUntil = original.validUntil,
                            status = AwardStatus.REVOKED,
                            revokesAwardId = awardId,
                            grantedBy = revokedBy,
                            awardedAt = revokedAt,
                        ),
                )
            RankingPointAwardsTable.selectAll().where { RankingPointAwardsTable.id eq markerId }.single().toRankingPointAward()
        }

    /**
     * One page of the whole ledger (#472), newest-first by `awarded_at`, plus the total row count.
     * Backs the Points Management "Points awarded" list; includes REVOKED markers so the full trail
     * shows. One count query + one windowed select.
     */
    fun listAwards(
        limit: Int,
        offset: Int,
    ): Pair<List<RankingPointAward>, Long> =
        transaction {
            val total = RankingPointAwardsTable.selectAll().count()
            val rows =
                RankingPointAwardsTable
                    .selectAll()
                    .orderBy(RankingPointAwardsTable.awardedAt to SortOrder.DESC)
                    .limit(n = limit, offset = offset.toLong())
                    .map { it.toRankingPointAward() }
            rows to total
        }

    /**
     * The still-ACTIVE award rows produced by [eventId] (#477): finalize's award rows for this event
     * that have not been revoked. Backs un-finalize's reversal, which revokes each one. REVOKED
     * originals and REVOKED markers drop out (only ACTIVE rows carry live points).
     */
    fun listActiveByEvent(eventId: UUID): List<RankingPointAward> =
        transaction {
            RankingPointAwardsTable
                .selectAll()
                .where {
                    (RankingPointAwardsTable.eventId eq eventId) and
                        (RankingPointAwardsTable.status eq AwardStatus.ACTIVE.name)
                }.map { it.toRankingPointAward() }
        }

    /** Every ledger row for [userId], newest first (awarded_at). Includes REVOKED markers for the trail. */
    fun listByUser(userId: UUID): List<RankingPointAward> =
        transaction {
            RankingPointAwardsTable
                .selectAll()
                .where { RankingPointAwardsTable.userId eq userId }
                .orderBy(RankingPointAwardsTable.awardedAt to SortOrder.DESC)
                .map { it.toRankingPointAward() }
        }

    /**
     * Every award that counts as of [asOf] — ACTIVE and with [asOf] inside [valid_from, valid_until).
     * Backs the phase-2 standings recompute; the REVOKED markers (and revoked originals) drop out.
     */
    fun activeAsOf(asOf: LocalDateTime): List<RankingPointAward> =
        transaction {
            RankingPointAwardsTable
                .selectAll()
                .where {
                    (RankingPointAwardsTable.status eq AwardStatus.ACTIVE.name) and
                        (RankingPointAwardsTable.validFrom lessEq asOf) and
                        (RankingPointAwardsTable.validUntil greater asOf)
                }.map { it.toRankingPointAward() }
        }

    /**
     * One user's awards that count as of [asOf] (#448): ACTIVE and with [asOf] inside
     * [valid_from, valid_until), soonest-to-expire first so the profile audit shows the most urgent
     * points at the top. The REVOKED markers (and revoked originals) drop out.
     */
    fun listActiveByUser(
        userId: UUID,
        asOf: LocalDateTime,
    ): List<RankingPointAward> =
        transaction {
            RankingPointAwardsTable
                .selectAll()
                .where {
                    (RankingPointAwardsTable.userId eq userId) and
                        (RankingPointAwardsTable.status eq AwardStatus.ACTIVE.name) and
                        (RankingPointAwardsTable.validFrom lessEq asOf) and
                        (RankingPointAwardsTable.validUntil greater asOf)
                }.orderBy(RankingPointAwardsTable.validUntil to SortOrder.ASC)
                .map { it.toRankingPointAward() }
        }

    private fun insertRow(write: RankingPointAwardWrite): UUID =
        RankingPointAwardsTable.insertAndGetId {
            it[userId] = write.userId
            it[points] = write.points
            it[pointClass] = write.pointClass.name
            it[sourceType] = write.sourceType.name
            it[sourceId] = write.sourceId
            it[band] = write.band
            it[sex] = write.sex
            it[reason] = write.reason
            it[validFrom] = write.validFrom
            it[validUntil] = write.validUntil
            it[status] = write.status.name
            it[revokesAwardId] = write.revokesAwardId
            it[grantedBy] = write.grantedBy
            it[awardedAt] = write.awardedAt
            it[eventId] = write.eventId
            it[matchId] = write.matchId
        }.value
}

internal fun ResultRow.toRankingPointAward(): RankingPointAward =
    RankingPointAward(
        id = this[RankingPointAwardsTable.id].value,
        userId = this[RankingPointAwardsTable.userId].value,
        points = this[RankingPointAwardsTable.points],
        pointClass = PointClass.valueOf(value = this[RankingPointAwardsTable.pointClass]),
        sourceType = PointSourceType.valueOf(value = this[RankingPointAwardsTable.sourceType]),
        sourceId = this[RankingPointAwardsTable.sourceId],
        band = this[RankingPointAwardsTable.band],
        sex = this[RankingPointAwardsTable.sex],
        reason = this[RankingPointAwardsTable.reason],
        validFrom = this[RankingPointAwardsTable.validFrom],
        validUntil = this[RankingPointAwardsTable.validUntil],
        status = AwardStatus.valueOf(value = this[RankingPointAwardsTable.status]),
        revokesAwardId = this[RankingPointAwardsTable.revokesAwardId],
        grantedBy = this[RankingPointAwardsTable.grantedBy]?.value,
        awardedAt = this[RankingPointAwardsTable.awardedAt],
        eventId = this[RankingPointAwardsTable.eventId]?.value,
        matchId = this[RankingPointAwardsTable.matchId]?.value,
    )
