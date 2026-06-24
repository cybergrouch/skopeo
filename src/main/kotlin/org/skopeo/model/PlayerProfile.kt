// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import kotlinx.serialization.Serializable

@Serializable
data class PlayerProfile(
    val playerId: String,
    val name: String,
    val rating: Rating,
) {
    init {
        require(value = playerId.isNotBlank()) { "Player ID must not be blank" }
        require(value = playerId.length <= 50) { "Player ID must be at most 50 characters" }
        require(value = name.isNotBlank()) { "Player name must not be blank" }
        require(value = name.length <= 100) { "Player name must be at most 100 characters" }
    }
}
