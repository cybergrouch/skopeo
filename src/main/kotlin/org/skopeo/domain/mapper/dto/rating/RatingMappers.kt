// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.rating

import org.skopeo.domain.model.Level
import org.skopeo.domain.model.PendingAssessment
import org.skopeo.domain.model.PendingAssessmentPage
import org.skopeo.domain.model.RatingHistoryEntry
import org.skopeo.domain.model.UserRating
import org.skopeo.dto.rating.PendingAssessmentPageResponse
import org.skopeo.dto.rating.PendingAssessmentResponse
import org.skopeo.dto.rating.RatingHistoryResponse
import org.skopeo.dto.rating.UserRatingResponse

/**
 * Map a rating for the API. [revealRawValue] gates the exact rating (#114): only rating managers
 * (ADMINISTRATOR) see it; players get the band + [UserRatingResponse.bandPosition].
 */
fun UserRating.toResponse(revealRawValue: Boolean): UserRatingResponse =
    UserRatingResponse(
        value = if (revealRawValue) currentRating.toPlainString() else null,
        level = currentLevel,
        bandPosition = Level.positionInBand(rating = currentRating),
        confidence = confidence.toPlainString(),
        matchesPlayed = matchesPlayed,
        lastMatchDate = lastMatchDate?.toString(),
    )

fun RatingHistoryEntry.toResponse(revealRawValue: Boolean = false): RatingHistoryResponse =
    RatingHistoryResponse(
        id = id.toString(),
        matchId = matchId?.toString(),
        // Raw NTRP values + the per-set analysis internals are ADMINISTRATOR-only (#583); non-admins
        // get the band change (previousLevel/newLevel/levelChanged) only.
        previousRating = if (revealRawValue) previousRating.toPlainString() else null,
        newRating = if (revealRawValue) newRating.toPlainString() else null,
        ratingChange = if (revealRawValue) ratingChange.toPlainString() else null,
        percentChange = if (revealRawValue) percentChange?.toPlainString() else null,
        previousLevel = previousLevel,
        newLevel = newLevel,
        levelChanged = levelChanged,
        dominanceFactor = if (revealRawValue) dominanceFactor?.toPlainString() else null,
        smoothingApplied = smoothingApplied,
        smoothingFactor = if (revealRawValue) smoothingFactor?.toPlainString() else null,
        setBreakdown = if (revealRawValue) setBreakdown.map { it.toResponse() } else emptyList(),
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
        proposedRating = proposedRating,
    )

fun PendingAssessmentPage.toResponse(): PendingAssessmentPageResponse =
    PendingAssessmentPageResponse(items = items.map { it.toResponse() }, total = total)
