package org.lange.tennis.levelr.dto

import kotlinx.serialization.Serializable
import org.lange.tennis.levelr.model.PlayerProfile

@Serializable
data class RankingCalculationResponse(
    val players: Map<String, PlayerProfile>,
    val ratingChanges: Map<String, RatingChange>,
)
