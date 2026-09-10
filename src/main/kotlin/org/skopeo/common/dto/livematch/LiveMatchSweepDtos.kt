// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.dto.livematch

import kotlinx.serialization.Serializable

/**
 * Body for `POST /api/v1/live-matches/sweep` — prune finalized scoring logs (#939).
 *
 * **Dry run by default**, matching the rating and standings triggers. A sweep deletes rows; the safe
 * reading of an absent or malformed body is "tell me what you would do", and only an explicit
 * `{"dryRun": false}` removes anything.
 */
@Serializable
data class LiveMatchSweepRequest(
    val dryRun: Boolean = true,
    /**
     * Override the retention window in days. Absent uses the default.
     *
     * Present mainly so an operator can widen it in an incident ("keep everything from the last year
     * while we investigate") without a deploy — narrowing it below the default is the dangerous
     * direction and is exactly why the dry run exists.
     */
    val retentionDays: Int? = null,
)

/** What a sweep did, or would do. */
@Serializable
data class LiveMatchSweepResponse(
    val dryRun: Boolean,
    val retentionDays: Int,
    /** Matches whose log is old enough and whose result is recorded. */
    val prunedMatches: Int,
    /** Log rows removed. Zero on a dry run, however many matches were listed. */
    val prunedRows: Int,
)
