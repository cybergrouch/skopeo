// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.skopeo.domain.model.MatchStatus
import org.skopeo.repository.persistence.MatchReplayEntity
import java.time.LocalDateTime
import java.util.UUID

/**
 * Data access for `match_replays` (#1145). Pure data access: the document is a JSON string in and out,
 * and building, decoding and upgrading it is `MatchReplayService`'s job.
 */
class MatchReplayRepository {
    fun find(matchId: UUID): MatchReplayEntity? =
        transaction {
            MatchReplaysTable
                .selectAll()
                .where { MatchReplaysTable.matchId eq matchId }
                .singleOrNull()
                ?.toEntity()
        }

    /** Write the replay for [matchId], replacing any earlier one — a regenerated or upgraded document. */
    fun save(
        matchId: UUID,
        formatVersion: Int,
        replay: String,
        now: LocalDateTime = LocalDateTime.now(),
    ) {
        transaction {
            val exists = MatchReplaysTable.selectAll().where { MatchReplaysTable.matchId eq matchId }.any()
            if (exists) {
                MatchReplaysTable.update(where = { MatchReplaysTable.matchId eq matchId }) {
                    it[MatchReplaysTable.formatVersion] = formatVersion
                    it[MatchReplaysTable.replay] = replay
                    it[MatchReplaysTable.updatedAt] = now
                }
            } else {
                MatchReplaysTable.insert {
                    it[MatchReplaysTable.matchId] = matchId
                    it[MatchReplaysTable.formatVersion] = formatVersion
                    it[MatchReplaysTable.replay] = replay
                    it[MatchReplaysTable.createdAt] = now
                    it[MatchReplaysTable.updatedAt] = now
                }
            }
        }
    }

    /**
     * Finished matches that still have a scoring log but no replay — what a backfill can record.
     *
     * "Finished" means a result is recorded, the same test the retention sweep uses, so a match the sweep
     * could delete is always one this can save first.
     */
    fun unrecordedWithLog(): List<UUID> =
        transaction {
            LiveMatchEventsTable
                .join(
                    otherTable = MatchesTable,
                    joinType = JoinType.INNER,
                    onColumn = LiveMatchEventsTable.matchId,
                    otherColumn = MatchesTable.id,
                ).join(
                    otherTable = MatchReplaysTable,
                    joinType = JoinType.LEFT,
                    onColumn = LiveMatchEventsTable.matchId,
                    otherColumn = MatchReplaysTable.matchId,
                ).select(columns = listOf(element = LiveMatchEventsTable.matchId))
                .where { (MatchesTable.status notInList UNFINISHED) and MatchReplaysTable.matchId.isNull() }
                .withDistinct()
                .map { it[LiveMatchEventsTable.matchId].value }
        }

    /** Replays stored in a format older than [currentVersion] — what an upgrade regenerates. */
    fun outdated(currentVersion: Int): List<UUID> =
        transaction {
            MatchReplaysTable
                .select(columns = listOf(element = MatchReplaysTable.matchId))
                .where { MatchReplaysTable.formatVersion less currentVersion }
                .map { it[MatchReplaysTable.matchId].value }
        }

    private fun ResultRow.toEntity(): MatchReplayEntity =
        MatchReplayEntity(
            matchId = this[MatchReplaysTable.matchId].value,
            formatVersion = this[MatchReplaysTable.formatVersion],
            replay = this[MatchReplaysTable.replay],
            createdAt = this[MatchReplaysTable.createdAt],
            updatedAt = this[MatchReplaysTable.updatedAt],
        )

    private companion object {
        val UNFINISHED = listOf(MatchStatus.SCHEDULED.name, MatchStatus.IN_PROGRESS.name)
    }
}
