package org.lange.tennis.levelr.model

import kotlinx.serialization.Serializable

@Serializable
data class Rating(
    val value: Double,
    val system: RatingSystem,
) {
    init {
        when (system) {
            RatingSystem.NTRP -> {
                require(value in 1.0..7.0) { "NTRP rating must be between 1.0 and 7.0, got $value" }
            }
            RatingSystem.UTR -> {
                require(value >= 1.0) { "UTR rating must be at least 1.0, got $value" }
            }
        }
    }
}
