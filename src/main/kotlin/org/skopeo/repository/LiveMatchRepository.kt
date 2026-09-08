// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.skopeo.repository.persistence.LiveMatchEventEntity
import org.skopeo.repository.persistence.LiveMatchScorerEntity
import org.skopeo.repository.persistence.MatchUmpireEntity
import java.time.LocalDateTime
import java.util.UUID

/**
 * Data access for the live-scoring log, the scorer claim, and the durable umpire credit (#911).
 *
 * Pure data access: strings in, strings out. Nothing here knows what a `POINT_WON` means — folding a log
 * into a score is [org.skopeo.domain.service.livematch.ScoreEngine]'s job, and turning rows into
 * `LoggedAction`s is the entity mapper's.
 */
open class LiveMatchRepository {
    /** The whole log for [matchId], in sequence order. The only read pattern that matters. */
    fun log(matchId: UUID): List<LiveMatchEventEntity> =
        transaction {
            LiveMatchEventsTable
                .selectAll()
                .where { LiveMatchEventsTable.matchId eq matchId }
                .orderBy(LiveMatchEventsTable.sequence to SortOrder.ASC)
                .map { it.toLiveMatchEvent() }
        }

    /**
     * Append one row at [sequence], returning false if that sequence was already taken.
     *
     * **The race is settled by the database, not by this code.** Two Cloud Run instances can compute the
     * same next sequence from the same log and both insert; `uq_live_match_events_sequence` lets exactly
     * one win, and the loser is told so here rather than silently overwriting or interleaving. A caller
     * that gets `false` must re-read the log and retry against what actually landed — see
     * [appendNext], which does that.
     *
     * Catching the constraint violation rather than pre-checking with a SELECT is deliberate: a
     * check-then-insert has a window between the two, which is the whole bug being avoided.
     */
    @Suppress("LongParameterList")
    open fun append(
        matchId: UUID,
        sequence: Long,
        kind: String,
        side: String? = null,
        playerId: UUID? = null,
        targetSequence: Long? = null,
        recordedBy: UUID,
        recordedAt: LocalDateTime = LocalDateTime.now(),
    ): Boolean =
        try {
            transaction {
                LiveMatchEventsTable.insert {
                    it[LiveMatchEventsTable.matchId] = matchId
                    it[LiveMatchEventsTable.sequence] = sequence
                    it[LiveMatchEventsTable.kind] = kind
                    it[LiveMatchEventsTable.side] = side
                    it[LiveMatchEventsTable.playerId] = playerId
                    it[LiveMatchEventsTable.targetSequence] = targetSequence
                    it[LiveMatchEventsTable.recordedBy] = recordedBy
                    it[LiveMatchEventsTable.recordedAt] = recordedAt
                }
            }
            true
        } catch (_: ExposedSQLException) {
            false
        }

    /** The highest sequence written for [matchId], or 0 when the log is empty. */
    fun lastSequence(matchId: UUID): Long {
        val sequence = LiveMatchEventsTable.sequence
        return transaction {
            LiveMatchEventsTable
                .selectAll()
                .where { LiveMatchEventsTable.matchId eq matchId }
                .orderBy(sequence to SortOrder.DESC)
                .limit(count = 1)
                .firstOrNull()
                ?.let { it[sequence] } ?: 0L
        }
    }

    /** Who currently holds the soft claim on [matchId], if anyone. */
    fun scorer(matchId: UUID): LiveMatchScorerEntity? =
        transaction {
            LiveMatchScorersTable
                .selectAll()
                .where { LiveMatchScorersTable.matchId eq matchId }
                .firstOrNull()
                ?.let {
                    LiveMatchScorerEntity(
                        matchId = it[LiveMatchScorersTable.matchId].value,
                        scorerId = it[LiveMatchScorersTable.scorerId].value,
                        claimedAt = it[LiveMatchScorersTable.claimedAt],
                    )
                }
        }

    /**
     * Claim [matchId] for [scorerId], displacing whoever held it.
     *
     * An upsert rather than an insert-if-absent, because taking over is the *expected* path, not an error
     * (§ the soft-claim decision): a phone dies courtside and someone else picks the match up.
     */
    fun claim(
        matchId: UUID,
        scorerId: UUID,
        claimedAt: LocalDateTime = LocalDateTime.now(),
    ) {
        transaction {
            LiveMatchScorersTable.upsert {
                it[LiveMatchScorersTable.matchId] = matchId
                it[LiveMatchScorersTable.scorerId] = scorerId
                it[LiveMatchScorersTable.claimedAt] = claimedAt
            }
        }
    }

    /** Release the claim on [matchId]. Idempotent — releasing an unclaimed match is not an error. */
    fun releaseClaim(matchId: UUID) {
        transaction { LiveMatchScorersTable.deleteWhere { LiveMatchScorersTable.matchId eq matchId } }
    }

    /**
     * Record who umpired [matchId], folded from the log.
     *
     * Written at finalize so the credit outlives the log, which §8a makes disposable. Replaces any
     * existing rows for the match: a re-finalize after a correction should restate the credit rather than
     * double it.
     */
    fun recordUmpires(
        matchId: UUID,
        umpires: List<MatchUmpireEntity>,
    ) {
        transaction {
            MatchUmpiresTable.deleteWhere { MatchUmpiresTable.matchId eq matchId }
            umpires.forEach { umpire ->
                MatchUmpiresTable.insert {
                    it[MatchUmpiresTable.matchId] = matchId
                    it[userId] = umpire.userId
                    it[eventsRecorded] = umpire.eventsRecorded
                    it[firstRecordedAt] = umpire.firstRecordedAt
                    it[lastRecordedAt] = umpire.lastRecordedAt
                }
            }
        }
    }

    /** Who umpired [matchId], most active first. Survives disposal of the log. */
    fun umpires(matchId: UUID): List<MatchUmpireEntity> =
        transaction {
            MatchUmpiresTable
                .selectAll()
                .where { MatchUmpiresTable.matchId eq matchId }
                .orderBy(MatchUmpiresTable.eventsRecorded to SortOrder.DESC)
                .map {
                    MatchUmpireEntity(
                        userId = it[MatchUmpiresTable.userId].value,
                        eventsRecorded = it[MatchUmpiresTable.eventsRecorded],
                        firstRecordedAt = it[MatchUmpiresTable.firstRecordedAt],
                        lastRecordedAt = it[MatchUmpiresTable.lastRecordedAt],
                    )
                }
        }

    /**
     * Discard the log for [matchId] once it is no longer working state (§8a).
     *
     * Safe to call after [recordUmpires] and only then: the umpire credit is folded out of the log first,
     * so disposal loses the keystrokes but not the attribution.
     */
    fun discardLog(matchId: UUID) {
        transaction { LiveMatchEventsTable.deleteWhere { LiveMatchEventsTable.matchId eq matchId } }
    }

    private fun ResultRow.toLiveMatchEvent(): LiveMatchEventEntity =
        LiveMatchEventEntity(
            sequence = this[LiveMatchEventsTable.sequence],
            kind = this[LiveMatchEventsTable.kind],
            side = this[LiveMatchEventsTable.side],
            playerId = this[LiveMatchEventsTable.playerId]?.value,
            targetSequence = this[LiveMatchEventsTable.targetSequence],
            recordedBy = this[LiveMatchEventsTable.recordedBy].value,
            recordedAt = this[LiveMatchEventsTable.recordedAt],
        )
}
