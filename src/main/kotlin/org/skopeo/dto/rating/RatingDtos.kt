// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.rating

import kotlinx.serialization.Serializable
import org.skopeo.model.PendingAssessment
import org.skopeo.model.PendingAssessmentPage
import org.skopeo.model.RatingHistoryEntry
import org.skopeo.model.UserRating

/** Body for `PUT /api/v1/users/{userId}/ratings` — an administrator sets/adjusts a rating. */
@Serializable
data class SetRatingRequest(
    val value: String,
    val confidence: String? = null,
)

@Serializable
data class UserRatingResponse(
    val value: String,
    val level: String? = null,
    val confidence: String,
    val matchesPlayed: Int,
    val lastMatchDate: String? = null,
)

@Serializable
data class RatingHistoryResponse(
    val id: String,
    val matchId: String? = null,
    val previousRating: String,
    val newRating: String,
    val ratingChange: String,
    val percentChange: String? = null,
    val previousLevel: String? = null,
    val newLevel: String? = null,
    val levelChanged: Boolean,
    val dominanceFactor: String? = null,
    val smoothingApplied: Boolean,
    val smoothingFactor: String? = null,
    val calculatedAt: String,
)

@Serializable
data class PendingAssessmentResponse(
    val userId: String,
    val publicCode: String,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val sex: String? = null,
    val dateOfBirth: String? = null,
    val age: Int? = null,
)

/** A page of pending assessments with the total count, so the admin UI can paginate. */
@Serializable
data class PendingAssessmentPageResponse(
    val items: List<PendingAssessmentResponse>,
    val total: Int,
)

fun UserRating.toResponse(): UserRatingResponse =
    UserRatingResponse(
        value = currentRating.toPlainString(),
        level = currentLevel,
        confidence = confidence.toPlainString(),
        matchesPlayed = matchesPlayed,
        lastMatchDate = lastMatchDate?.toString(),
    )

fun RatingHistoryEntry.toResponse(): RatingHistoryResponse =
    RatingHistoryResponse(
        id = id.toString(),
        matchId = matchId?.toString(),
        previousRating = previousRating.toPlainString(),
        newRating = newRating.toPlainString(),
        ratingChange = ratingChange.toPlainString(),
        percentChange = percentChange?.toPlainString(),
        previousLevel = previousLevel,
        newLevel = newLevel,
        levelChanged = levelChanged,
        dominanceFactor = dominanceFactor?.toPlainString(),
        smoothingApplied = smoothingApplied,
        smoothingFactor = smoothingFactor?.toPlainString(),
        calculatedAt = calculatedAt.toString(),
    )

fun PendingAssessment.toResponse(): PendingAssessmentResponse =
    PendingAssessmentResponse(
        userId = userId.toString(),
        publicCode = publicCode,
        displayName = displayName,
        photoUrl = photoUrl,
        sex = sex,
        dateOfBirth = dateOfBirth?.toString(),
        age = age,
    )

fun PendingAssessmentPage.toResponse(): PendingAssessmentPageResponse =
    PendingAssessmentPageResponse(items = items.map { it.toResponse() }, total = total)
