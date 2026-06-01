package org.lange.tennis.levelr.dto

import kotlinx.serialization.Serializable
import org.lange.tennis.levelr.model.Rating

@Serializable
data class RatingChange(
    val change: Double,
    val percentChange: Double,
    val previousRating: Rating,
    val newRating: Rating,
)
