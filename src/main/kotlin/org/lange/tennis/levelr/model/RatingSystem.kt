package org.lange.tennis.levelr.model

import kotlinx.serialization.Serializable

@Serializable
enum class RatingSystem {
    NTRP, // National Tennis Rating Program (1.0 - 7.0)
    UTR, // Universal Tennis Rating (1.0 - 16.5+)
}
