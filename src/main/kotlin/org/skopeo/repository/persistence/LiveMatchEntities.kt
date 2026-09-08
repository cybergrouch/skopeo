// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.time.LocalDateTime
import java.util.UUID

/**
 * One row of `live_match_events` (#911), exactly as stored.
 *
 * Raw and untyped on purpose: `kind` and `side` are the database's strings, not the domain's sealed
 * types. Turning them into a `LoggedAction` is `mapper.entity`'s job, which keeps the repository a pure
 * data-access layer under `LayeredArchitectureTest`.
 */
data class LiveMatchEventEntity(
    val sequence: Long,
    val kind: String,
    val side: String?,
    val playerId: UUID?,
    val targetSequence: Long?,
    val recordedBy: UUID,
    val recordedAt: LocalDateTime,
)

/** A row of `live_match_scorers` — who currently holds the soft claim on a match (#911). */
data class LiveMatchScorerEntity(
    val matchId: UUID,
    val scorerId: UUID,
    val claimedAt: LocalDateTime,
)

/** A row of `match_umpires` — durable credit for scoring a match, folded from the log at finalize (#911). */
data class MatchUmpireEntity(
    val userId: UUID,
    val eventsRecorded: Int,
    val firstRecordedAt: LocalDateTime,
    val lastRecordedAt: LocalDateTime,
)
