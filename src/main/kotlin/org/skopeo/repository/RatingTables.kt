// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

// NUMERIC precision/scale mirror the V1 schema.
private const val RATING_PRECISION = 10
private const val RATING_SCALE = 6
private const val CONFIDENCE_PRECISION = 3
private const val CONFIDENCE_SCALE = 2
private const val LEVEL_MAX = 10

/** Current rating per user (NTRP-only). Flyway owns the DDL; this maps only what the repository touches. */
internal object UserRatingsTable : UUIDTable(name = "user_ratings") {
    val userId = reference(name = "user_id", foreign = UsersTable, onDelete = ReferenceOption.CASCADE)
    val currentRating = decimal(name = "current_rating", precision = RATING_PRECISION, scale = RATING_SCALE)
    val currentLevel = varchar(name = "current_level", length = LEVEL_MAX).nullable()
    val confidenceScore =
        decimal(
            name = "confidence_score",
            precision = CONFIDENCE_PRECISION,
            scale = CONFIDENCE_SCALE,
        ).default(defaultValue = java.math.BigDecimal("0.50"))
    val matchesPlayed = integer(name = "matches_played").default(defaultValue = 0)
    val lastMatchDate = date(name = "last_match_date").nullable()
}

/** Append-only rating-change history (match-driven, or initial assessment when match_id is null). */
internal object UserRatingHistoryTable : UUIDTable(name = "user_rating_history") {
    val userId = reference(name = "user_id", foreign = UsersTable, onDelete = ReferenceOption.CASCADE)
    val matchId = uuid(name = "match_id").nullable()
    val previousRating = decimal(name = "previous_rating", precision = RATING_PRECISION, scale = RATING_SCALE)
    val newRating = decimal(name = "new_rating", precision = RATING_PRECISION, scale = RATING_SCALE)
    val ratingChange = decimal(name = "rating_change", precision = RATING_PRECISION, scale = RATING_SCALE)
    val percentChange = decimal(name = "percent_change", precision = RATING_PRECISION, scale = RATING_SCALE).nullable()
    val previousLevel = varchar(name = "previous_level", length = LEVEL_MAX).nullable()
    val newLevel = varchar(name = "new_level", length = LEVEL_MAX).nullable()
    val levelChanged = bool(name = "level_changed").default(defaultValue = false)
    val dominanceFactor = decimal(name = "dominance_factor", precision = RATING_PRECISION, scale = RATING_SCALE).nullable()
    val smoothingApplied = bool(name = "smoothing_applied").default(defaultValue = false)
    val smoothingFactor = decimal(name = "smoothing_factor", precision = CONFIDENCE_PRECISION, scale = CONFIDENCE_SCALE).nullable()
    val calculatedAt = datetime(name = "calculated_at")
}
