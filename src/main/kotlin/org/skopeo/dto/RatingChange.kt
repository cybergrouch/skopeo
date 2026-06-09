package org.skopeo.dto

import kotlinx.serialization.Serializable
import org.skopeo.model.Rating

/**
 * Represents a rating change result for a player after a match.
 *
 * Includes both the continuous rating change and discrete level assignment.
 * The rating objects contain their associated levels (rating.level).
 *
 * ## v1 Implementation (Current)
 * - Levels calculated dynamically from rating values
 * - Level changes immediately when rating crosses boundary
 * - No persistence required
 *
 * ## v2 Implementation (Future)
 * - Will separate dynamic rating from published level
 * - Published level updated on schedule (e.g., nightly, weekly)
 * - Requires database to track published vs dynamic values
 * - Rating and level stored together as cohesive unit
 */
@Serializable
data class RatingChange(
    val change: String,
    val previousRating: Rating,
    val newRating: Rating,
    val percentChange: String,
    val levelChanged: Boolean,
)
