// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.dto.match

import kotlinx.serialization.Serializable

/**
 * Body for `POST /api/v1/match-replays/backfill` (#1145): record a replay for every finished match that
 * still has a scoring log and none yet, and regenerate any stored in an older format.
 *
 * **Dry run by default**, like every other admin trigger: an absent or malformed body previews.
 */
@Serializable
data class MatchReplayBackfillRequest(
    val dryRun: Boolean = true,
)

/** What a backfill did, or would do. */
@Serializable
data class MatchReplayBackfillResponse(
    val dryRun: Boolean,
    /** Matches given a replay for the first time. */
    val recorded: Int,
    /** Stored replays regenerated into the current format. */
    val upgraded: Int,
    /** Logs that never reach an ending, so there is no result to replay to. Left as they are. */
    val skipped: Int,
)
