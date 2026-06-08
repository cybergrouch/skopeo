package org.lange.tennis.levelr.dto

import kotlinx.serialization.Serializable
import org.lange.tennis.levelr.model.PlayerProfile
import org.lange.tennis.levelr.model.Team

@Serializable
data class RankingCalculationResponse(
    val ratingChanges: Map<String, RatingChange>,
    val players: Map<String, PlayerProfile>,
    val teams: Map<String, Team>,
)
