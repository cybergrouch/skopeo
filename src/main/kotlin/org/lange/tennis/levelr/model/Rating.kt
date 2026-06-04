package org.lange.tennis.levelr.model

import kotlinx.serialization.Serializable

@Serializable
data class Rating(
    val value: String,
    val system: RatingSystem,
) {
    init {
        // Validate that value is a valid number
        val numericValue =
            value.toDouble()
                ?: throw IllegalArgumentException("Rating value must be a valid number, got '$value'")

        // Validate system-specific constraints
        when (system) {
            RatingSystem.NTRP -> {
                require(numericValue in 1.0..7.0) { "NTRP rating must be between 1.0 and 7.0, got $value" }
            }
            RatingSystem.UTR -> {
                require(numericValue in 1.0..16.0) { "UTR rating must be between 1.0 and 16.0, got $value" }
            }
        }
    }
}
