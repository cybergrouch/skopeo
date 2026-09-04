// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.dto.match

import kotlinx.serialization.Serializable

/**
 * Body for `POST /api/v1/matches/{id}/score-correction` (#776) — correct the score of an ALREADY-RATED
 * match. [dryRun] defaults to **true**, mirroring the rating-calculation trigger: a correction is
 * destructive to rating state, so the caller must ask for the write explicitly.
 */
@Serializable
data class MatchScoreCorrectionRequest(
    val sets: List<SetScoreRequest>,
    val dryRun: Boolean = true,
) {
    init {
        // Shape validation at the boundary (#116): a corrected result must still report at least one set.
        require(value = sets.isNotEmpty()) { "at least one set is required" }
    }
}

/**
 * One player's rating impact from a score correction (#776), as previewed or applied.
 *
 * The arithmetic is a swap of one delta for another: [reversedChange] (the delta originally applied, taken
 * verbatim from the match's live history row) comes off, and [newChange] (recomputed for the corrected
 * score from the player's rating AT THE TIME, not their present-day rating) goes on. [netAdjustment] is
 * `newChange - reversedChange` — what actually moves the current rating.
 */
@Serializable
data class MatchCorrectionPlayerImpact(
    val userId: String,
    val displayName: String? = null,
    val currentRating: String,
    val reversedChange: String,
    val newChange: String,
    val netAdjustment: String,
    val resultingRating: String,
    val previousLevel: String? = null,
    val resultingLevel: String? = null,
    val levelChanged: Boolean = false,
    /**
     * True when **no rating was applied to this player for this match**, because calibration suppressed
     * it (#881) — so there is nothing to reverse and nothing to re-apply, and the correction leaves them
     * untouched.
     *
     * `reversedChange` and `netAdjustment` are then "0" and `resultingRating` equals `currentRating`.
     * Without this flag those zeroes are indistinguishable from a correction that happened to cancel out,
     * which is the difference an administrator previewing a correction most needs to see.
     *
     * A player suppressed at rating time stays suppressed on correction: the decision belongs to the
     * state as it was then, not to the state now — otherwise correcting a match after its calibration
     * window closed would retroactively move a settled opponent's rating.
     */
    val wasSuppressed: Boolean = false,
)

/**
 * The outcome of a score correction (#776). With `dryRun = true` (the default) nothing was written and
 * this is the confirmation surface the admin approves; with `dryRun = false` it reports what was applied.
 */
@Serializable
data class MatchScoreCorrectionResponse(
    val dryRun: Boolean,
    val matchPublicCode: String,
    val previousScore: String,
    val newScore: String,
    // True when the corrected score moves the win to the other side.
    val winnerChanged: Boolean,
    val impacts: List<MatchCorrectionPlayerImpact>,
    // Ranking points revoked / re-issued by this correction; both 0 when the match pays no points.
    val awardsRevoked: Int = 0,
    val awardsReissued: Int = 0,
    // Set when the event opted into points but the global kill switch (#641/#752) suppressed the re-issue,
    // so the caller is told the points were not re-paid rather than silently seeing zero.
    val pointsSuppressedByGlobalFlag: Boolean = false,
)
