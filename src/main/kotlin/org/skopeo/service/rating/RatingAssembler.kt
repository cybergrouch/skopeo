// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

@file:Suppress("TooManyFunctions") // Cohesive facade over RatingRepository: mirrors its read/write surface 1:1 (#633).

package org.skopeo.service.rating

import org.skopeo.mapper.entity.rating.toDomain
import org.skopeo.model.MatchRatingWrite
import org.skopeo.model.PreEventRating
import org.skopeo.model.RatingHistoryEntry
import org.skopeo.model.RatingHistoryWrite
import org.skopeo.model.UserRating
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Domain-facing facade over [RatingRepository] (#633). The repository is now pure data access returning
 * raw `persistence` entities; this assembler re-exposes every method with the SAME signature + domain
 * return type the old repository had, so consumers swap `RatingRepository` → `RatingAssembler` with no
 * other change.
 *
 * It also OWNS the windowed-match fetch that backs the computed `UserRating.confidence` (#459) — logic that
 * used to live in `RatingRepository`. That fetch (from [matches]) can't sit in the `mapper.entity` boundary
 * (mappers don't touch the DB) nor in the repository under the flip (`repository ↛ mapper`), so the
 * assembler pulls the rows (single or batched, never N+1), reads the entity from [ratings], and maps via
 * `UserRatingEntity.toDomain(windowed, now)`.
 */
class RatingAssembler(
    private val ratings: RatingRepository = RatingRepository(),
    private val matches: MatchRepository = MatchRepository(),
) {
    /** The user's ratings as a list (0 or 1) — the API surfaces a collection. */
    fun findByUser(userId: UUID): List<UserRating> {
        val now = LocalDateTime.now()
        val windowed = matches.windowedMatchesInWindow(userId = userId, asOf = now)
        return ratings.findByUser(userId = userId).map { it.toDomain(windowed = windowed, now = now) }
    }

    fun findCurrentRating(userId: UUID): UserRating? {
        val now = LocalDateTime.now()
        return ratings.findCurrentRating(userId = userId)?.toDomain(
            windowed = matches.windowedMatchesInWindow(userId = userId, asOf = now),
            now = now,
        )
    }

    /** Every user's current rating — backs the per-band standings (#113). Confidence counts are batched. */
    fun allCurrentRatings(): List<UserRating> {
        val now = LocalDateTime.now()
        val entities = ratings.allCurrentRatings()
        val windowedByUser = matches.windowedMatchesInWindow(userIds = entities.map { it.userId }, asOf = now)
        return entities.map { it.toDomain(windowed = windowedByUser[it.userId].orEmpty(), now = now) }
    }

    /** Current ratings for many users at once, keyed by user id; users without a rating are absent. */
    fun findCurrentRatings(userIds: List<UUID>): Map<UUID, UserRating> {
        if (userIds.isEmpty()) return emptyMap()
        val now = LocalDateTime.now()
        val windowedByUser = matches.windowedMatchesInWindow(userIds = userIds, asOf = now)
        return ratings.findCurrentRatings(userIds = userIds).mapValues { (uid, entity) ->
            entity.toDomain(windowed = windowedByUser[uid].orEmpty(), now = now)
        }
    }

    /**
     * Set a rating directly (admin/RATER assessment or override, #343). Delegates the write to [ratings]
     * and maps the resulting row to domain, deriving confidence from the user's windowed match counts so a
     * returned override reflects their recent play (#459).
     */
    fun setRating(
        userId: UUID,
        rating: BigDecimal,
        level: String?,
    ): UserRating {
        val entity = ratings.setRating(userId = userId, rating = rating, level = level)
        val now = LocalDateTime.now()
        return entity.toDomain(windowed = matches.windowedMatchesInWindow(userId = userId, asOf = now), now = now)
    }

    fun historyByUser(userId: UUID): List<RatingHistoryEntry> = ratings.historyByUser(userId = userId).map { it.toDomain() }

    fun allHistory(): List<RatingHistoryEntry> = ratings.allHistory().map { it.toDomain() }

    fun historyForMatches(matchIds: List<UUID>): List<RatingHistoryEntry> =
        ratings.historyForMatches(matchIds = matchIds).map { it.toDomain() }

    fun preEventRatings(eventId: UUID): Map<UUID, PreEventRating> = ratings.preEventRatings(eventId = eventId)

    fun isEventAtRatedTip(eventId: UUID): Boolean = ratings.isEventAtRatedTip(eventId = eventId)

    fun userIdsPendingAssessment(
        limit: Int,
        offset: Int,
    ): Pair<List<UUID>, Long> = ratings.userIdsPendingAssessment(limit = limit, offset = offset)

    fun markEventHistoryReversed(
        eventId: UUID,
        reversedAt: LocalDateTime,
    ): Int = ratings.markEventHistoryReversed(eventId = eventId, reversedAt = reversedAt)

    fun restoreCurrentRating(
        userId: UUID,
        rating: BigDecimal,
        level: String?,
        lastMatchDate: LocalDate?,
    ): Unit = ratings.restoreCurrentRating(userId = userId, rating = rating, level = level, lastMatchDate = lastMatchDate)

    fun applyMatchRating(write: MatchRatingWrite): Unit = ratings.applyMatchRating(write = write)

    fun appendHistory(write: RatingHistoryWrite): Unit = ratings.appendHistory(write = write)
}
