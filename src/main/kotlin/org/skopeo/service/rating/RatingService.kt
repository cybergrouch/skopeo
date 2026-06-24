// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.rating

import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.PendingAssessment
import org.skopeo.model.Rating
import org.skopeo.model.RatingHistoryEntry
import org.skopeo.model.UserRating
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.ForbiddenException
import org.skopeo.service.user.UserNotFoundException
import org.skopeo.service.user.VerifiedFirebaseToken
import java.math.BigDecimal
import java.util.UUID

private val DEFAULT_CONFIDENCE = BigDecimal("0.50")

/**
 * Reading ratings/history is self-or-ADMINISTRATOR; setting a rating (the initial
 * assessment, or a later adjustment) and listing users pending assessment are
 * ADMINISTRATOR-only. A user with no rating is "pending assessment" and is ineligible to
 * be entered into a match (enforced by the match flow).
 */
class RatingService(
    private val ratings: RatingRepository = RatingRepository(),
    private val users: UserRepository = UserRepository(),
) {
    fun getRatings(
        token: VerifiedFirebaseToken,
        userId: UUID,
    ): List<UserRating> {
        requireUserExists(userId = userId)
        requireSelfOrAdmin(token = token, userId = userId)
        return ratings.findByUser(userId = userId)
    }

    fun getHistory(
        token: VerifiedFirebaseToken,
        userId: UUID,
    ): List<RatingHistoryEntry> {
        requireUserExists(userId = userId)
        requireSelfOrAdmin(token = token, userId = userId)
        return ratings.historyByUser(userId = userId)
    }

    /** Set (or adjust) a user's rating — ADMINISTRATOR only. Computes the published level. */
    fun setRating(
        token: VerifiedFirebaseToken,
        userId: UUID,
        value: String,
        confidence: String?,
    ): UserRating {
        requireAdmin(token = token)
        requireUserExists(userId = userId)
        // Rating.fromValue validates the NTRP range and derives the published level.
        val level =
            try {
                Rating.fromValue(value = value).publishedLevel.value
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid rating '$value'", e)
            }
        val confidenceValue = parseConfidence(confidence = confidence)
        return ratings.setRating(
            userId = userId,
            rating = BigDecimal(value),
            level = level,
            confidence = confidenceValue,
        )
    }

    fun pendingAssessment(token: VerifiedFirebaseToken): List<PendingAssessment> {
        requireAdmin(token = token)
        return ratings.userIdsPendingAssessment().map { id ->
            val displayName =
                users.findById(id = id)?.names?.firstOrNull { it.type == NameType.DISPLAY && it.isActive }?.value
            PendingAssessment(userId = id, displayName = displayName)
        }
    }

    private fun parseConfidence(confidence: String?): BigDecimal {
        val value = confidence?.let { BigDecimal(it) } ?: return DEFAULT_CONFIDENCE
        require(value = value >= BigDecimal.ZERO && value <= BigDecimal.ONE) { "confidence must be between 0 and 1" }
        return value
    }

    private fun requireUserExists(userId: UUID) {
        users.findById(id = userId) ?: throw UserNotFoundException(id = userId)
    }

    private fun requireSelfOrAdmin(
        token: VerifiedFirebaseToken,
        userId: UUID,
    ) {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        val isSelf = caller?.id == userId
        val isAdmin = caller?.capabilities?.contains(element = Capability.ADMINISTRATOR) == true
        if (!isSelf && !isAdmin) throw ForbiddenException()
    }

    private fun requireAdmin(token: VerifiedFirebaseToken) {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        if (caller == null || !caller.capabilities.contains(element = Capability.ADMINISTRATOR)) throw ForbiddenException()
    }
}
