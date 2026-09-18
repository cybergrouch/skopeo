// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * Maintenance of `user_ratings.calibration_matches_rated` — the stored rated-match count that makes
 * calibration queryable (#1051).
 *
 * A denormalisation has exactly one failure mode: the cache and the truth disagree, invisibly. Two
 * decisions here are aimed at that, and both matter more than the column itself.
 *
 * **1. Recompute, never increment.** Every function below re-runs
 * [MatchRepository.countRatedMatchesSince] — the single definition of "a match that counts as rated" —
 * and overwrites the stored value with its answer. So the stored count cannot drift from the derived one
 * by arithmetic: it *is* the derived one, materialised. An increment at the write site would have to be
 * paired with a matching decrement on all three reversal paths (#477 un-finalize, #478 reverse-ratings,
 * #911 score correction) and would double-count anything applied twice. Recomputing is idempotent, so
 * calling it twice, or on a path that changed nothing, is free and harmless.
 *
 * **2. Maintained in the REPOSITORY, not in the services.** These are called from the write methods that
 * change which matches are rated, so no service can forget:
 *
 * | Write path | Called from | Why the count moves |
 * |---|---|---|
 * | `MatchRepository.markRated` | a committed rating calculation | a match becomes rated |
 * | `MatchRepository.clearRatedForEvent` | "Reverse Ratings" (#478) | the event's matches stop being rated |
 * | `MatchRepository.setActive` | match soft-delete **and restore** | an inactive match counts for nothing |
 * | `EventRepository.setActive` | event soft-delete, club delete's cascade | an inactive container hides its matches |
 * | `UserRepository.repointTeamUsers` | claim / replace / merge | participation moves between accounts |
 * | `RatingRepository.setRating` | every manual designation | the window restarts, so the count is 0 |
 * | `RatingRepository.inheritEarlierCalibrationStart` | account merge (#881) | the window moves earlier |
 *
 * Two of those are the reason this is not in the service layer. `ClubService.delete` cascades onto every
 * event and match of the club with **no rated-match guard**, and `MatchService.setActive` re-enables a
 * soft-deleted match with no guard either — both would have been easy to miss from above, and neither has
 * to know calibration exists from here.
 *
 * Deliberately NOT hooked: un-finalize (#477) refuses outright once any of the event's matches are rated,
 * so the rated set cannot change on that path; and a score correction (#911) leaves `rated_at`,
 * `is_active` and `team_users` untouched — a corrected match is still a rated match, so the count is
 * right without doing anything. Both are asserted rather than assumed.
 */
internal fun refreshCalibrationCounts(userIds: Collection<UUID>) {
    if (userIds.isEmpty()) {
        return
    }
    transaction {
        // Only players with an open window have a count to maintain; for everyone else the column is
        // unread (a null stamp answers "not calibrating" on its own), so there is nothing to write.
        val startedByUser =
            UserRatingsTable
                .select(columns = listOf(UserRatingsTable.userId, UserRatingsTable.calibrationStartedAt))
                .where {
                    (UserRatingsTable.userId inList userIds.distinct()) and UserRatingsTable.calibrationStartedAt.isNotNull()
                }.mapNotNull { row ->
                    row[UserRatingsTable.calibrationStartedAt]?.let { row[UserRatingsTable.userId].value to it }
                }.toMap()
        if (startedByUser.isEmpty()) {
            return@transaction
        }
        // The batch overload (#1050): one query for the whole set, each user measured from their own
        // designation. A user with no rated matches is absent from its result, hence the `?: 0`.
        val counts = MatchRepository().countRatedMatchesSince(sinceByUser = startedByUser)
        startedByUser.keys.forEach { userId ->
            UserRatingsTable.update(where = { UserRatingsTable.userId eq userId }) {
                it[calibrationMatchesRated] = counts[userId] ?: 0
            }
        }
    }
}

/** Refresh the stored count for everyone who played in [matchIds] — the per-match write paths. */
internal fun refreshCalibrationCountsForMatches(matchIds: Collection<UUID>) {
    if (matchIds.isEmpty()) {
        return
    }
    transaction {
        refreshCalibrationCounts(userIds = participantsOfMatches(matchIds = matchIds))
    }
}

/**
 * Refresh the stored count for everyone who played in [eventId]'s matches.
 *
 * Resolved from `team_users`, not from the event's rating-history rows — which is the whole point.
 * A calibration-suppressed player has **no** history row for a match they genuinely played (that absence
 * is what lets a correction reverse only what was applied), so a history-derived participant set would
 * miss exactly the players whose calibration count is at stake. Inactive matches are included too: their
 * participants' counts are recomputed correctly either way, and filtering them out would only risk
 * missing someone.
 */
internal fun refreshCalibrationCountsForEvent(eventId: UUID) {
    transaction {
        val matchIds =
            MatchesTable
                .select(columns = listOf(element = MatchesTable.id))
                .where { MatchesTable.eventId eq eventId }
                .map { it[MatchesTable.id].value }
        refreshCalibrationCountsForMatches(matchIds = matchIds)
    }
}

/** Every user on either side of any of [matchIds]. Must be called inside a transaction. */
private fun participantsOfMatches(matchIds: Collection<UUID>): List<UUID> {
    val teamIds =
        MatchesTable
            .select(columns = listOf(MatchesTable.team1Id, MatchesTable.team2Id))
            .where { MatchesTable.id inList matchIds.distinct() }
            .flatMap { listOf(it[MatchesTable.team1Id].value, it[MatchesTable.team2Id].value) }
    if (teamIds.isEmpty()) {
        return emptyList()
    }
    return TeamUsersTable
        .select(columns = listOf(element = TeamUsersTable.userId))
        .where { TeamUsersTable.teamId inList teamIds.distinct() }
        .map { it[TeamUsersTable.userId].value }
        .distinct()
}
