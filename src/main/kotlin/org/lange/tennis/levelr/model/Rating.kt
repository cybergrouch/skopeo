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
            value.toDoubleOrNull()
                ?: throw IllegalArgumentException("Rating value must be a valid number, got '$value'")

        // Validate system-specific constraints
        when (system) {
            RatingSystem.NTRP -> {
                require(numericValue in 1.0..7.0) { "NTRP rating must be between 1.0 and 7.0, got $value" }
            }
            RatingSystem.UTR -> {
                require(numericValue >= 1.0) { "UTR rating must be at least 1.0, got $value" }
            }
        }
    }

    // Helper to get numeric value for calculations
    fun toDouble(): Double = value.toDouble()
}
