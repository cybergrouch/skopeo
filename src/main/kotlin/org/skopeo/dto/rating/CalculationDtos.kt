// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.rating

import kotlinx.serialization.Serializable
import org.skopeo.dto.match.MatchResponse

/**
 * Body for `POST /api/v1/ratings/calculations`. [dryRun] defaults to true (preview only);
 * set it explicitly to false to commit the rating changes.
 *
 * [eventIds] optionally scopes the run to a selection of finalized events (#479). When null or empty,
 * behaviour is unchanged: every pending match is processed, oldest→newest. When provided, only those
 * events' pending matches are previewed/committed — but ratings must still carry forward on a
 * consistent timeline, so the selection MUST form a **contiguous prefix of the pending timeline**: you
 * may not select an event while an OLDER pending match (an earlier event, or an eventless "Open" match)
 * is left out. A selection that skips an earlier pending match is rejected with a validation error
 * naming the earliest excluded match. The same guard applies to both the preview and the commit, so a
 * preview reflects exactly what a commit would do.
 */
@Serializable
data class CalculationRequest(
    val dryRun: Boolean = true,
    val eventIds: List<String>? = null,
)

@Serializable
data class PlayerChangeResponse(
    val userId: String,
    val previousRating: String,
    val newRating: String,
    val change: String,
    val percentChange: String,
    val previousLevel: String? = null,
    val newLevel: String? = null,
    val levelChanged: Boolean,
    val breakdown: CalculationBreakdownResponse,
)

/**
 * The internal calculator derivatives behind a player's change (issue #89). The net fields are present
 * for the v1 (averaged) calculator and null for v2, which reports the per-set steps in [sets] (#110).
 */
@Serializable
data class CalculationBreakdownResponse(
    val dominance: String? = null,
    val scale: String? = null,
    val ratingGap: String? = null,
    val normalizedGap: String? = null,
    val competitiveThresholdPct: String? = null,
    val isUpset: Boolean? = null,
    val upsetMultiplier: String? = null,
    val kFactor: String? = null,
    val sets: List<SetBreakdownResponse> = emptyList(),
)

/** One set's calculator derivatives behind a player's change (v2 per-set calculator, issue #110). */
@Serializable
data class SetBreakdownResponse(
    val setIndex: Int,
    val score: String,
    val dominance: String,
    val scale: String,
    val ratingGap: String,
    val normalizedGap: String,
    val competitiveThresholdPct: String,
    val isUpset: Boolean,
    val upsetMultiplier: String,
    val kFactor: String,
    val delta: String,
    val ratingAfter: String,
)

@Serializable
data class MatchCalculationResponse(
    val matchId: String,
    val matchDate: String,
    val changes: List<PlayerChangeResponse>,
)

/** The detail behind a rating-history entry (#97): the match result plus the stored calculation. */
@Serializable
data class MatchCalculationDetailResponse(
    val match: MatchResponse,
    val changes: List<MatchPlayerCalculationResponse>,
)

/** One player's stored calculation for a rated match, with their display name. */
@Serializable
data class MatchPlayerCalculationResponse(
    val userId: String,
    val displayName: String? = null,
    val previousRating: String,
    val newRating: String,
    val change: String,
    val percentChange: String? = null,
    val previousLevel: String? = null,
    val newLevel: String? = null,
    val levelChanged: Boolean,
    // Absent for rows that predate the persisted breakdown (#97) or initial assessments.
    val breakdown: CalculationBreakdownResponse? = null,
)

@Serializable
data class CalculationResponse(
    val dryRun: Boolean,
    val matchesProcessed: Int,
    val matches: List<MatchCalculationResponse>,
)
