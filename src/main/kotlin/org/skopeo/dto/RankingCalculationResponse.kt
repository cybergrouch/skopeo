package org.skopeo.dto

import kotlinx.serialization.Serializable
import org.skopeo.model.PlayerProfile
import org.skopeo.model.Team

@Serializable
data class RankingCalculationResponse(
    val ratingChanges: Map<String, RatingChange>,
    val players: Map<String, PlayerProfile>,
    val teams: Map<String, Team>,
)
