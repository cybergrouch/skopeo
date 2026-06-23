// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.model.RatingHistoryEntry
import org.skopeo.model.RatingHistoryWrite
import org.skopeo.model.RatingSystem
import org.skopeo.model.UserRating
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Persistence for current ratings and rating history. Initial ratings are admin-set
 * (no auto-seed); [setRating] upserts the per-(user, system) row. History is read here and
 * written by the match flow.
 */
class RatingRepository {
    fun findByUser(userId: UUID): List<UserRating> =
        transaction {
            UserRatingsTable
                .selectAll()
                .where { UserRatingsTable.userId eq userId }
                .map { it.toUserRating() }
        }

    fun findByUserAndSystem(
        userId: UUID,
        system: RatingSystem,
    ): UserRating? = transaction { ratingRow(userId, system)?.toUserRating() }

    /** Insert or update the user's rating in a system (admin assessment). */
    fun setRating(
        userId: UUID,
        system: RatingSystem,
        rating: BigDecimal,
        level: String?,
        confidence: BigDecimal,
    ): UserRating =
        transaction {
            if (ratingRow(userId, system) == null) {
                UserRatingsTable.insert {
                    it[UserRatingsTable.userId] = userId
                    it[ratingSystem] = system.name
                    it[currentRating] = rating
                    it[currentLevel] = level
                    it[confidenceScore] = confidence
                }
            } else {
                UserRatingsTable.update(
                    { (UserRatingsTable.userId eq userId) and (UserRatingsTable.ratingSystem eq system.name) },
                ) {
                    it[currentRating] = rating
                    it[currentLevel] = level
                    it[confidenceScore] = confidence
                }
            }
            ratingRow(userId, system)!!.toUserRating()
        }

    fun historyByUser(
        userId: UUID,
        system: RatingSystem?,
    ): List<RatingHistoryEntry> =
        transaction {
            UserRatingHistoryTable
                .selectAll()
                .where {
                    if (system == null) {
                        UserRatingHistoryTable.userId eq userId
                    } else {
                        (UserRatingHistoryTable.userId eq userId) and (UserRatingHistoryTable.ratingSystem eq system.name)
                    }
                }.orderBy(UserRatingHistoryTable.calculatedAt to SortOrder.DESC)
                .map { it.toRatingHistory() }
        }

    /** Active users with no rating on record yet — pending an administrator's initial assessment. */
    fun userIdsPendingAssessment(): List<UUID> =
        transaction {
            val rated = UserRatingsTable.selectAll().map { it[UserRatingsTable.userId].value }.toSet()
            UsersTable
                .selectAll()
                .where { UsersTable.isActive eq true }
                .map { it[UsersTable.id].value }
                .filterNot { it in rated }
        }

    /** Apply a match-driven rating update: set the new rating/level, bump matches played + last match date. */
    fun applyMatchRating(
        userId: UUID,
        system: RatingSystem,
        newRating: BigDecimal,
        newLevel: String?,
        matchDate: LocalDate,
    ) {
        transaction {
            UserRatingsTable.update(
                { (UserRatingsTable.userId eq userId) and (UserRatingsTable.ratingSystem eq system.name) },
            ) {
                with(SqlExpressionBuilder) { it[matchesPlayed] = matchesPlayed + 1 }
                it[currentRating] = newRating
                it[currentLevel] = newLevel
                it[lastMatchDate] = matchDate
            }
        }
    }

    fun appendHistory(write: RatingHistoryWrite) {
        transaction {
            UserRatingHistoryTable.insert {
                it[userId] = write.userId
                it[matchId] = write.matchId
                it[ratingSystem] = write.system.name
                it[previousRating] = write.previousRating
                it[newRating] = write.newRating
                it[ratingChange] = write.ratingChange
                it[percentChange] = write.percentChange
                it[previousLevel] = write.previousLevel
                it[newLevel] = write.newLevel
                it[levelChanged] = write.levelChanged
                it[calculatedAt] = write.calculatedAt
            }
        }
    }

    private fun ratingRow(
        userId: UUID,
        system: RatingSystem,
    ): ResultRow? =
        UserRatingsTable
            .selectAll()
            .where { (UserRatingsTable.userId eq userId) and (UserRatingsTable.ratingSystem eq system.name) }
            .singleOrNull()
}

internal fun ResultRow.toUserRating(): UserRating =
    UserRating(
        userId = this[UserRatingsTable.userId].value,
        system = RatingSystem.valueOf(this[UserRatingsTable.ratingSystem]),
        currentRating = this[UserRatingsTable.currentRating],
        currentLevel = this[UserRatingsTable.currentLevel],
        confidence = this[UserRatingsTable.confidenceScore],
        matchesPlayed = this[UserRatingsTable.matchesPlayed],
        lastMatchDate = this[UserRatingsTable.lastMatchDate],
    )

internal fun ResultRow.toRatingHistory(): RatingHistoryEntry =
    RatingHistoryEntry(
        id = this[UserRatingHistoryTable.id].value,
        userId = this[UserRatingHistoryTable.userId].value,
        matchId = this[UserRatingHistoryTable.matchId],
        system = RatingSystem.valueOf(this[UserRatingHistoryTable.ratingSystem]),
        previousRating = this[UserRatingHistoryTable.previousRating],
        newRating = this[UserRatingHistoryTable.newRating],
        ratingChange = this[UserRatingHistoryTable.ratingChange],
        percentChange = this[UserRatingHistoryTable.percentChange],
        previousLevel = this[UserRatingHistoryTable.previousLevel],
        newLevel = this[UserRatingHistoryTable.newLevel],
        levelChanged = this[UserRatingHistoryTable.levelChanged],
        dominanceFactor = this[UserRatingHistoryTable.dominanceFactor],
        smoothingApplied = this[UserRatingHistoryTable.smoothingApplied],
        smoothingFactor = this[UserRatingHistoryTable.smoothingFactor],
        calculatedAt = this[UserRatingHistoryTable.calculatedAt],
    )
