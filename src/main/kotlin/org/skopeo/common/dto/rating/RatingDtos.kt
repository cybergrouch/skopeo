// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.dto.rating

import kotlinx.serialization.Serializable

/**
 * Body for `PUT /api/v1/users/{userId}/ratings` — a RATER/ADMINISTRATOR sets/adjusts a rating.
 * Provide [band] (the normal path, #206) to store the band midpoint, or [value] for a precise
 * override; exactly one is required.
 */
@Serializable
data class SetRatingRequest(
    val band: String? = null,
    val value: String? = null,
)

@Serializable
data class UserRatingResponse(
    // The exact rating is privacy-withheld from players (#64/#114): null unless the caller manages
    // ratings (ADMINISTRATOR). Players get the band ([level]) and a normalized [bandPosition] instead.
    val value: String? = null,
    val level: String? = null,
    // Normalized 0..1 position within the current NTRP band (floor 0 → ceiling 1) for the own-profile
    // "speed meter" (#114); never reveals the exact rating.
    val bandPosition: Double? = null,
    val confidence: String,
    val matchesPlayed: Int,
    val lastMatchDate: String? = null,
)

@Serializable
data class RatingHistoryResponse(
    val id: String,
    val matchId: String? = null,
    // Raw NTRP values — ADMINISTRATOR-only (#583); null for non-admins, who see the band change only.
    val previousRating: String? = null,
    val newRating: String? = null,
    val ratingChange: String? = null,
    val percentChange: String? = null,
    val previousLevel: String? = null,
    val newLevel: String? = null,
    val levelChanged: Boolean,
    val dominanceFactor: String? = null,
    val smoothingApplied: Boolean,
    val smoothingFactor: String? = null,
    // Per-set calculation steps (#110); empty for v1/initial/pre-#110 rows.
    val setBreakdown: List<SetBreakdownResponse> = emptyList(),
    val calculatedAt: String,
    /**
     * The public code of the account this entry was recorded against (#853). Normally the viewed player's
     * own; for an entry inherited from an account merged into them (#643) it is that account's code.
     * Null only when the account could not be resolved.
     */
    val sourcePublicCode: String? = null,
    /**
     * True when this entry came from an account merged into the viewed player (#853).
     *
     * Load-bearing, not decorative: the two trajectories do **not** chain. The merged-away account ended
     * at its own last rating and the survivor keeps its own, so joining them into one line would draw a
     * band change that never happened to the person. Only the survivor's series is current.
     */
    val fromMergedAccount: Boolean = false,
)

@Serializable
data class PendingAssessmentResponse(
    val userId: String,
    val publicCode: String,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val sex: String? = null,
    val dateOfBirth: String? = null,
    val age: Int? = null,
    val proposedRating: String? = null,
)

/** A page of pending assessments with the total count, so the admin UI can paginate. */
@Serializable
data class PendingAssessmentPageResponse(
    val items: List<PendingAssessmentResponse>,
    val total: Int,
)
