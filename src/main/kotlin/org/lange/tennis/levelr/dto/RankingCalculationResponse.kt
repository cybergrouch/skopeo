package org.lange.tennis.levelr.dto

import kotlinx.serialization.Serializable

@Serializable
data class RankingCalculationResponse(
    val ratingChanges: Map<String, RatingChange>,
)
