// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.dto

import kotlinx.serialization.Serializable
import org.skopeo.domain.model.PlayerProfile
import org.skopeo.domain.model.Team

@Serializable
data class RankingCalculationResponse(
    val ratingChanges: Map<String, RatingChange>,
    val players: Map<String, PlayerProfile>,
    val teams: Map<String, Team>,
)
