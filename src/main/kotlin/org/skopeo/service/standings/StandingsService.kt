// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.standings

import org.skopeo.model.BandStandings
import org.skopeo.model.StandingEntry
import org.skopeo.model.StandingsBand
import org.skopeo.model.User
import org.skopeo.model.UserRating
import org.skopeo.model.ageInYears
import org.skopeo.model.displayName
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import java.time.LocalDate
import java.util.UUID

/**
 * The per-band "Ranking Race" standings (issue #113), visible to any signed-in player. **Interim,
 * ratings-derived**: within each NTRP band, active rated players are ordered by current rating
 * (highest first, with deterministic tie-breaks) and assigned a rank. The exact rating is never
 * exposed — only the order — consistent with the rating-privacy design (#64/#114). The intended
 * evolution to an independent, points-based ranking is tracked separately (Phase 2).
 */
class StandingsService(
    private val users: UserRepository = UserRepository(),
    private val ratings: RatingRepository = RatingRepository(),
) {
    fun standings(): List<BandStandings> {
        val current = ratings.allCurrentRatings().associateBy { it.userId }
        val today = LocalDate.now()
        // Only active, rated players race; a disabled duplicate (#124) drops out.
        val active = users.findAllByIds(ids = current.keys.toList()).filter { it.isActive }
        val byBand = active.groupBy { StandingsBand.of(rating = current.getValue(key = it.id).currentRating) }

        // Strongest band first; within a band, split by sex (Men, Women, then any Unspecified) and rank
        // each group independently. Empty (band, sex) groups are omitted (#212).
        return StandingsBand.entries.reversed().flatMap { band ->
            byBand[band]
                .orEmpty()
                .groupBy { it.sex }
                .entries
                .sortedBy { sexOrder(sex = it.key) }
                .map { (sex, group) ->
                    BandStandings(band = band, sex = sex, entries = rankEntries(players = group, current = current, today = today))
                }
        }
    }

    /** Rank a single (band, sex) group from 1 by rating → confidence → matches → name. */
    private fun rankEntries(
        players: List<User>,
        current: Map<UUID, UserRating>,
        today: LocalDate,
    ): List<StandingEntry> =
        players
            .sortedWith(comparator = standingsComparator(current = current))
            .mapIndexed { index, user ->
                StandingEntry(
                    rank = index + 1,
                    userId = user.id,
                    displayName = user.displayName(),
                    publicCode = user.publicCode,
                    sex = user.sex,
                    age = user.dateOfBirth?.let { ageInYears(dateOfBirth = it, asOf = today) },
                )
            }

    private fun standingsComparator(current: Map<UUID, UserRating>): Comparator<User> =
        Comparator { left, right ->
            val leftRating = current.getValue(key = left.id)
            val rightRating = current.getValue(key = right.id)
            var order = rightRating.currentRating.compareTo(other = leftRating.currentRating)
            if (order == 0) order = rightRating.confidence.compareTo(other = leftRating.confidence)
            if (order == 0) order = rightRating.matchesPlayed.compareTo(other = leftRating.matchesPlayed)
            if (order == 0) order = standingName(user = left).compareTo(other = standingName(user = right))
            order
        }

    /** Sex group ordering for the standings: Men, then Women, then the Unspecified group. */
    private fun sexOrder(sex: String?): Int =
        when (sex) {
            "Male" -> 0
            "Female" -> 1
            else -> 2
        }

    private fun standingName(user: User): String = user.displayName() ?: user.publicCode
}
