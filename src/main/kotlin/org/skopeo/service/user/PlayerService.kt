// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import org.skopeo.dto.user.PublicPlayerResponse
import org.skopeo.dto.user.PublicRatingDto
import org.skopeo.model.NameType
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.ResourceNotFoundException

/**
 * Resolves a player's shareable, auth-gated public profile from their [public code] (issue #61).
 * Open to any authenticated user (the route is behind auth); returns only a privacy-conscious
 * subset, not the full account.
 */
class PlayerService(
    private val users: UserRepository = UserRepository(),
    private val ratings: RatingRepository = RatingRepository(),
) {
    fun publicProfile(code: String): PublicPlayerResponse {
        val normalized = code.trim().uppercase()
        val user =
            users.findByPublicCode(code = normalized)?.takeIf { it.isActive }
                ?: throw ResourceNotFoundException(message = "No player with code $normalized")
        val rating = ratings.findCurrentRating(userId = user.id)
        return PublicPlayerResponse(
            publicCode = user.publicCode,
            displayName = user.names.firstOrNull { it.type == NameType.DISPLAY && it.isActive }?.value,
            photoUrl = user.photoUrl,
            rating = rating?.let { PublicRatingDto(value = it.currentRating.toPlainString(), level = it.currentLevel) },
        )
    }
}
