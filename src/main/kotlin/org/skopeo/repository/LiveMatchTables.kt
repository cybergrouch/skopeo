// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

private const val KIND_MAX = 24
private const val SIDE_MAX = 8

/**
 * The append-only umpire action log for a live match (#911, V55).
 *
 * Working state, not a record of results: finalize translates it through the existing `uploadResult`, so
 * `matches`/`match_sets` stay the system of record (`LIVE_MATCH.md` §8a). Rows are never updated or
 * deleted — an undo appends a row naming the sequence it cancels.
 *
 * The `(match_id, sequence)` unique constraint is the concurrency guard: two Cloud Run instances can
 * write at the same instant, and only the database can settle that. See the migration for why.
 */
internal object LiveMatchEventsTable : UUIDTable(name = "live_match_events") {
    val matchId = reference(name = "match_id", foreign = MatchesTable, onDelete = ReferenceOption.CASCADE)
    val sequence = long(name = "sequence")
    val kind = varchar(name = "kind", length = KIND_MAX)

    /** TEAM1/TEAM2. Never left/right — a side-flip in the umpire view must not corrupt the log (§6). */
    val side = varchar(name = "side", length = SIDE_MAX).nullable()

    /** SERVER_ASSIGNED only. A player, because in doubles the serve rotates through four of them. */
    val playerId = reference(name = "player_id", foreign = UsersTable, onDelete = ReferenceOption.RESTRICT).nullable()

    /**
     * UNDONE only. Deliberately not a reference: it names a `(match_id, sequence)` pair, and a marker
     * aimed at a sequence that was never written has to be inert rather than rejected.
     */
    val targetSequence = long(name = "target_sequence").nullable()

    val recordedBy = reference(name = "recorded_by", foreign = UsersTable, onDelete = ReferenceOption.RESTRICT)
    val recordedAt = datetime(name = "recorded_at")
}

/**
 * Who is keying a match in right now (#911, V55) — a **soft claim**, not a lock.
 *
 * `uq_live_match_events_sequence` is what protects the data; this exists so two umpires do not confuse
 * each other, and any authorized scorer may take over. A courtside phone that dies must not strand the
 * fixture behind a lock waiting for a timeout nobody chose well.
 */
internal object LiveMatchScorersTable : Table(name = "live_match_scorers") {
    val matchId = reference(name = "match_id", foreign = MatchesTable, onDelete = ReferenceOption.CASCADE)
    val scorerId = reference(name = "scorer_id", foreign = UsersTable, onDelete = ReferenceOption.RESTRICT)
    val claimedAt = datetime(name = "claimed_at")

    override val primaryKey = PrimaryKey(firstColumn = matchId)
}

/**
 * Who umpired a match, kept on the **match** rather than only on the log (#911, V55).
 *
 * Every log row carries `recorded_by`, but §8a makes the log disposable once the match is recorded — so
 * the log alone would attribute the umpire right up until the evidence is swept away. This is written at
 * finalize by folding the log, and outlives it on purpose.
 *
 * A row per (match, umpire) because a takeover is expected, and a match scored by two people should
 * credit both. The counts and timestamps are what separate "umpired the match" from "tapped one point
 * during a handover".
 */
internal object MatchUmpiresTable : Table(name = "match_umpires") {
    val matchId = reference(name = "match_id", foreign = MatchesTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference(name = "user_id", foreign = UsersTable, onDelete = ReferenceOption.RESTRICT)
    val eventsRecorded = integer(name = "events_recorded")
    val firstRecordedAt = datetime(name = "first_recorded_at")
    val lastRecordedAt = datetime(name = "last_recorded_at")

    override val primaryKey = PrimaryKey(matchId, userId)
}
