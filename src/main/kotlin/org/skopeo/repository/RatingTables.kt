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
private const val SYSTEM_MAX = 10
private const val LEVEL_MAX = 10

/** Current rating per (user, system). Flyway owns the DDL; this maps only what the repository touches. */
internal object UserRatingsTable : UUIDTable("user_ratings") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val ratingSystem = varchar("rating_system", SYSTEM_MAX)
    val currentRating = decimal("current_rating", RATING_PRECISION, RATING_SCALE)
    val currentLevel = varchar("current_level", LEVEL_MAX).nullable()
    val confidenceScore = decimal("confidence_score", CONFIDENCE_PRECISION, CONFIDENCE_SCALE).default(java.math.BigDecimal("0.50"))
    val matchesPlayed = integer("matches_played").default(0)
    val lastMatchDate = date("last_match_date").nullable()
}

/** Append-only rating-change history (match-driven, or initial assessment when match_id is null). */
internal object UserRatingHistoryTable : UUIDTable("user_rating_history") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val matchId = uuid("match_id").nullable()
    val ratingSystem = varchar("rating_system", SYSTEM_MAX)
    val previousRating = decimal("previous_rating", RATING_PRECISION, RATING_SCALE)
    val newRating = decimal("new_rating", RATING_PRECISION, RATING_SCALE)
    val ratingChange = decimal("rating_change", RATING_PRECISION, RATING_SCALE)
    val percentChange = decimal("percent_change", RATING_PRECISION, RATING_SCALE).nullable()
    val previousLevel = varchar("previous_level", LEVEL_MAX).nullable()
    val newLevel = varchar("new_level", LEVEL_MAX).nullable()
    val levelChanged = bool("level_changed").default(false)
    val dominanceFactor = decimal("dominance_factor", RATING_PRECISION, RATING_SCALE).nullable()
    val smoothingApplied = bool("smoothing_applied").default(false)
    val smoothingFactor = decimal("smoothing_factor", CONFIDENCE_PRECISION, CONFIDENCE_SCALE).nullable()
    val calculatedAt = datetime("calculated_at")
}
