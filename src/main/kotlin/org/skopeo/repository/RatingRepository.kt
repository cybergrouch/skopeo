// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.intLiteral
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.model.MatchRatingWrite
import org.skopeo.model.RatingHistoryEntry
import org.skopeo.model.RatingHistoryWrite
import org.skopeo.model.SetCalculationBreakdown
import org.skopeo.model.UserRating
import org.skopeo.model.WindowMatch
import org.skopeo.model.confidenceAt
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * Persistence for the user's (single, NTRP) current rating and rating history. Initial ratings
 * are admin-set (no auto-seed); [setRating] upserts the row. History is read here and written
 * by the match flow.
 *
 * Confidence (#459) is computed on read from each user's windowed match rows (date + weight class),
 * sourced from [matches] — so every rating read that surfaces confidence first fetches those rows
 * (batched for the multi-user reads, one query for the whole page, never N+1).
 */
class RatingRepository(
    private val matches: MatchRepository = MatchRepository(),
) {
    /** The user's ratings as a list (0 or 1) — the API surfaces a collection. */
    fun findByUser(userId: UUID): List<UserRating> =
        transaction {
            val now = LocalDateTime.now()
            val windowed = matches.windowedMatchesInWindow(userId = userId, asOf = now)
            UserRatingsTable
                .selectAll()
                .where { UserRatingsTable.userId eq userId }
                .map { it.toUserRating(windowed = windowed, now = now) }
        }

    fun findCurrentRating(userId: UUID): UserRating? =
        transaction {
            val now = LocalDateTime.now()
            ratingRow(userId = userId)?.toUserRating(
                windowed = matches.windowedMatchesInWindow(userId = userId, asOf = now),
                now = now,
            )
        }

    /** Every user's current rating — backs the per-band standings (#113). Confidence counts are batched. */
    fun allCurrentRatings(): List<UserRating> =
        transaction {
            val now = LocalDateTime.now()
            val rows = UserRatingsTable.selectAll().toList()
            val windowedByUser =
                matches.windowedMatchesInWindow(
                    userIds = rows.map { it[UserRatingsTable.userId].value },
                    asOf = now,
                )
            rows.map {
                it.toUserRating(windowed = windowedByUser[it[UserRatingsTable.userId].value].orEmpty(), now = now)
            }
        }

    /** Current ratings for many users at once, keyed by user id; users without a rating are absent. */
    fun findCurrentRatings(userIds: List<UUID>): Map<UUID, UserRating> =
        transaction {
            if (userIds.isEmpty()) {
                emptyMap()
            } else {
                val now = LocalDateTime.now()
                val windowedByUser = matches.windowedMatchesInWindow(userIds = userIds, asOf = now)
                UserRatingsTable
                    .selectAll()
                    .where { UserRatingsTable.userId inList userIds }
                    .associate { row ->
                        val uid = row[UserRatingsTable.userId].value
                        uid to row.toUserRating(windowed = windowedByUser[uid].orEmpty(), now = now)
                    }
            }
        }

    /**
     * Set a rating directly (admin/RATER assessment or override, #343). This is NOT a match-result
     * calculation, so it clears [UserRatingsTable.matchRatedAt]/[UserRatingsTable.matchesSinceReset]
     * (both now vestigial for confidence, #459 — left in place, no migration). Confidence is computed
     * from windowed match counts (#459), so a returned override reflects the user's recent play.
     */
    fun setRating(
        userId: UUID,
        rating: BigDecimal,
        level: String?,
    ): UserRating =
        transaction {
            if (ratingRow(userId = userId) == null) {
                UserRatingsTable.insert {
                    it[UserRatingsTable.userId] = userId
                    it[currentRating] = rating
                    it[currentLevel] = level
                    it[matchRatedAt] = null
                    it[matchesSinceReset] = 0
                }
            } else {
                UserRatingsTable.update(where = { UserRatingsTable.userId eq userId }) {
                    it[currentRating] = rating
                    it[currentLevel] = level
                    it[matchRatedAt] = null
                    it[matchesSinceReset] = 0
                }
            }
            val now = LocalDateTime.now()
            val row = UserRatingsTable.selectAll().where { UserRatingsTable.userId eq userId }.single()
            row.toUserRating(windowed = matches.windowedMatchesInWindow(userId = userId, asOf = now), now = now)
        }

    fun historyByUser(userId: UUID): List<RatingHistoryEntry> =
        transaction {
            UserRatingHistoryTable
                .selectAll()
                .where { UserRatingHistoryTable.userId eq userId }
                // Newest-first (#301). A calc batch stamps every row with one identical calculatedAt,
                // so completedAt breaks the intra-batch ties with the true chronology; match-less rows
                // (null completedAt) sort last, i.e. earliest.
                .orderBy(
                    UserRatingHistoryTable.calculatedAt to SortOrder.DESC,
                    UserRatingHistoryTable.completedAt to SortOrder.DESC_NULLS_LAST,
                ).map { it.toRatingHistory() }
        }

    /**
     * Every rating-history row across all users (#216 band-hop report), oldest first. Pilot-scale: the
     * report reconstructs each player's band at the window boundaries in memory, so it reads the table
     * in full; revisit with a windowed SQL query if the history grows large.
     */
    fun allHistory(): List<RatingHistoryEntry> =
        transaction {
            UserRatingHistoryTable
                .selectAll()
                .orderBy(UserRatingHistoryTable.calculatedAt to SortOrder.ASC)
                .map { it.toRatingHistory() }
        }

    /**
     * Every rating-history row tied to any of [matchIds] (across all users). Used to reconstruct
     * each side's at-the-time band for match history. Returns empty when [matchIds] is empty.
     */
    fun historyForMatches(matchIds: List<UUID>): List<RatingHistoryEntry> =
        transaction {
            if (matchIds.isEmpty()) {
                emptyList()
            } else {
                UserRatingHistoryTable
                    .selectAll()
                    .where { UserRatingHistoryTable.matchId inList matchIds }
                    .map { it.toRatingHistory() }
            }
        }

    /**
     * One page of active users with no rating on record yet — pending an administrator's initial
     * assessment — paired with the total count of all such users (for pagination). Ordered by id
     * for a stable page boundary.
     */
    fun userIdsPendingAssessment(
        limit: Int,
        offset: Int,
    ): Pair<List<UUID>, Long> =
        transaction {
            val rated = UserRatingsTable.selectAll().map { it[UserRatingsTable.userId].value }
            val pending = { UsersTable.selectAll().where { (UsersTable.isActive eq true) and (UsersTable.id notInList rated) } }
            val total = pending().count()
            val ids =
                pending()
                    .orderBy(UsersTable.id to SortOrder.ASC)
                    .limit(n = limit, offset = offset.toLong())
                    .map { it[UsersTable.id].value }
            ids to total
        }

    /**
     * Apply a match-driven rating update: set the new rating/level, bump matches played + last match
     * date, and stamp [MatchRatingWrite.ratedAt]. The `matchRatedAt` / `matchesSinceReset` columns are
     * still maintained here but are now **vestigial for confidence** (#459 — confidence keys off windowed
     * match counts, not these); they remain for the band-hop reset bookkeeping and are left in place (no
     * migration). The [MatchRatingWrite.bandJumped] reset (matches-since-reset → 0) is likewise retained
     * but no longer affects confidence.
     */
    fun applyMatchRating(write: MatchRatingWrite) {
        transaction {
            UserRatingsTable.update(where = { UserRatingsTable.userId eq write.userId }) {
                with(receiver = SqlExpressionBuilder) {
                    it[matchesPlayed] = matchesPlayed + 1
                    it[matchesSinceReset] = if (write.bandJumped) intLiteral(value = 0) else matchesSinceReset + 1
                }
                it[currentRating] = write.newRating
                it[currentLevel] = write.newLevel
                it[lastMatchDate] = write.matchDate
                it[matchRatedAt] = write.ratedAt
            }
        }
    }

    fun appendHistory(write: RatingHistoryWrite) {
        transaction {
            UserRatingHistoryTable.insert {
                it[userId] = write.userId
                it[matchId] = write.matchId
                it[previousRating] = write.previousRating
                it[newRating] = write.newRating
                it[ratingChange] = write.ratingChange
                it[percentChange] = write.percentChange
                it[previousLevel] = write.previousLevel
                it[newLevel] = write.newLevel
                it[levelChanged] = write.levelChanged
                // Persist the calculation breakdown (#97); dominance reuses the dominance_factor column.
                it[dominanceFactor] = write.breakdown?.dominance
                it[scale] = write.breakdown?.scale
                it[ratingGap] = write.breakdown?.ratingGap
                it[normalizedGap] = write.breakdown?.normalizedGap
                it[competitiveThresholdPct] = write.breakdown?.competitiveThresholdPct
                it[isUpset] = write.breakdown?.isUpset
                it[upsetMultiplier] = write.breakdown?.upsetMultiplier
                it[kFactor] = write.breakdown?.kFactor
                // Per-set breakdown (#110): JSON-encode when present (v2), else leave null (v1/initial).
                val steps = write.breakdown?.sets
                it[setBreakdown] =
                    if (steps.isNullOrEmpty()) {
                        null
                    } else {
                        RATING_HISTORY_JSON.encodeToString(serializer = SET_BREAKDOWN_SERIALIZER, value = steps)
                    }
                it[calculatedAt] = write.calculatedAt
                it[completedAt] = write.completedAt
            }
        }
    }

    private fun ratingRow(userId: UUID): ResultRow? =
        UserRatingsTable
            .selectAll()
            .where { UserRatingsTable.userId eq userId }
            .singleOrNull()
}

internal fun ResultRow.toUserRating(
    windowed: List<WindowMatch>,
    now: LocalDateTime,
): UserRating =
    UserRating(
        userId = this[UserRatingsTable.userId].value,
        currentRating = this[UserRatingsTable.currentRating],
        currentLevel = this[UserRatingsTable.currentLevel],
        // Computed on read (#459): 3-factor recency × sparsity × spacing over the player's windowed match
        // rows in the last 30 days; 0 when there is no qualifying play in the window.
        confidence = confidenceAt(matches = windowed, now = now),
        matchesPlayed = this[UserRatingsTable.matchesPlayed],
        lastMatchDate = this[UserRatingsTable.lastMatchDate],
        matchRatedAt = this[UserRatingsTable.matchRatedAt],
    )

internal fun ResultRow.toRatingHistory(): RatingHistoryEntry =
    RatingHistoryEntry(
        id = this[UserRatingHistoryTable.id].value,
        userId = this[UserRatingHistoryTable.userId].value,
        matchId = this[UserRatingHistoryTable.matchId],
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
        scale = this[UserRatingHistoryTable.scale],
        ratingGap = this[UserRatingHistoryTable.ratingGap],
        normalizedGap = this[UserRatingHistoryTable.normalizedGap],
        competitiveThresholdPct = this[UserRatingHistoryTable.competitiveThresholdPct],
        isUpset = this[UserRatingHistoryTable.isUpset],
        upsetMultiplier = this[UserRatingHistoryTable.upsetMultiplier],
        kFactor = this[UserRatingHistoryTable.kFactor],
        setBreakdown =
            this[UserRatingHistoryTable.setBreakdown]
                ?.let { RATING_HISTORY_JSON.decodeFromString(deserializer = SET_BREAKDOWN_SERIALIZER, string = it) }
                .orEmpty(),
        completedAt = this[UserRatingHistoryTable.completedAt],
        calculatedAt = this[UserRatingHistoryTable.calculatedAt],
    )

/** JSON codec and serializer for the per-set breakdown column (#110). */
private val RATING_HISTORY_JSON = Json
private val SET_BREAKDOWN_SERIALIZER = ListSerializer(elementSerializer = serializer<SetCalculationBreakdown>())
