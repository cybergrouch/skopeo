// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

// Entity→domain mappers for the rating aggregate (#633): builds the domain UserRating /
// RatingHistoryEntry from the raw persistence entities the repository returns. Lives in mapper.entity
// (which may depend on persistence + model + common); the service (RatingAssembler) calls it, since
// repository ↛ mapper. UserRating's computed `confidence` (#459) is derived here from the caller-supplied
// windowed match rows — the assembler owns that DB fetch, since the mapper can't hit the DB.

package org.skopeo.mapper.entity.rating

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.skopeo.model.RatingHistoryEntry
import org.skopeo.model.SetCalculationBreakdown
import org.skopeo.model.UserRating
import org.skopeo.model.WindowMatch
import org.skopeo.model.confidenceAt
import org.skopeo.repository.persistence.RatingHistoryEntryEntity
import org.skopeo.repository.persistence.UserRatingEntity
import java.time.LocalDateTime

/**
 * Convert the raw persistence [UserRatingEntity] to the domain [UserRating] (#633): this single boundary
 * is where the *computed* `confidence` is derived (#459) — a 3-factor recency × sparsity × spacing score
 * over the player's [windowed] match rows in the last 30 days, 0 when there is no qualifying play. The
 * direct analogue of `UserEntity.toDomain` computing `photoUrl`. The [windowed] rows and [now] are supplied
 * by the caller (RatingAssembler), which owns the DB fetch, since `mapper.entity` cannot touch the DB.
 */
fun UserRatingEntity.toDomain(
    windowed: List<WindowMatch>,
    now: LocalDateTime,
): UserRating =
    UserRating(
        userId = userId,
        currentRating = currentRating,
        currentLevel = currentLevel,
        confidence = confidenceAt(matches = windowed, now = now),
        matchesPlayed = matchesPlayed,
        lastMatchDate = lastMatchDate,
        matchRatedAt = matchRatedAt,
    )

/**
 * Convert the raw persistence [RatingHistoryEntryEntity] to the domain [RatingHistoryEntry] (#633): the
 * single boundary where the raw `setBreakdown` JSON (#110) is decoded into `List<SetCalculationBreakdown>`.
 */
fun RatingHistoryEntryEntity.toDomain(): RatingHistoryEntry =
    RatingHistoryEntry(
        id = id,
        userId = userId,
        matchId = matchId,
        previousRating = previousRating,
        newRating = newRating,
        ratingChange = ratingChange,
        percentChange = percentChange,
        previousLevel = previousLevel,
        newLevel = newLevel,
        levelChanged = levelChanged,
        dominanceFactor = dominanceFactor,
        smoothingApplied = smoothingApplied,
        smoothingFactor = smoothingFactor,
        scale = scale,
        ratingGap = ratingGap,
        normalizedGap = normalizedGap,
        competitiveThresholdPct = competitiveThresholdPct,
        isUpset = isUpset,
        upsetMultiplier = upsetMultiplier,
        kFactor = kFactor,
        setBreakdown =
            setBreakdown
                ?.let { RATING_HISTORY_JSON.decodeFromString(deserializer = SET_BREAKDOWN_SERIALIZER, string = it) }
                .orEmpty(),
        completedAt = completedAt,
        calculatedAt = calculatedAt,
    )

/** JSON codec and serializer for the per-set breakdown column (#110). */
private val RATING_HISTORY_JSON = Json
private val SET_BREAKDOWN_SERIALIZER = ListSerializer(elementSerializer = serializer<SetCalculationBreakdown>())
