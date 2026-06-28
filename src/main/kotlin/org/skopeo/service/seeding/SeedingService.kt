// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.seeding

import arrow.core.Either
import arrow.core.raise.either
import org.skopeo.model.Seeding
import org.skopeo.model.SeedingEntry
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.UserRating
import org.skopeo.model.ageInYears
import org.skopeo.model.displayName
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.SeedingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import java.time.LocalDate
import java.util.UUID
import kotlin.math.ceil

/**
 * Generates the seeding for a player list (issue #111). Only members that have a rating are seeded;
 * they are ordered highest-first (rating → confidence → matches → name) and the top ⌈N/2⌉ get bracket
 * seeds 1..⌈N/2⌉, the rest left blank. Regenerating overwrites the list's previous seeding.
 */
class SeedingService(
    private val lists: PlayerListService = PlayerListService(),
    private val users: UserRepository = UserRepository(),
    private val ratings: RatingRepository = RatingRepository(),
    private val seedings: SeedingRepository = SeedingRepository(),
) {
    fun generate(
        token: VerifiedFirebaseToken,
        listId: UUID,
    ): Either<ServiceError, Seeding> =
        either {
            val list = lists.get(token = token, listId = listId).bind() // ownership + access
            val members = users.findAllByIds(ids = list.memberUserIds)
            val currentRatings = ratings.findCurrentRatings(userIds = list.memberUserIds)
            val today = LocalDate.now()

            // Only rated members can be seeded; order them highest-first with deterministic tie-breaks.
            val ordered =
                members
                    .mapNotNull { user -> currentRatings[user.id]?.let { user to it } }
                    .sortedWith(
                        comparator =
                            compareByDescending<Pair<User, UserRating>> { it.second.currentRating }
                                .thenByDescending { it.second.confidence }
                                .thenByDescending { it.second.matchesPlayed }
                                .thenBy { it.first.displayName() ?: it.first.publicCode },
                    )

            val seededCount = ceil(x = ordered.size / 2.0).toInt()
            val entries =
                ordered.mapIndexed { index, (user, rating) ->
                    val position = index + 1
                    SeedingEntry(
                        seed = if (position <= seededCount) position else null,
                        position = position,
                        userId = user.id,
                        displayName = user.displayName(),
                        publicCode = user.publicCode,
                        ntrpBand = rating.currentLevel,
                        rating = rating.currentRating.toPlainString(),
                        sex = user.sex,
                        age = user.dateOfBirth?.let { ageInYears(dateOfBirth = it, asOf = today) },
                    )
                }
            seedings.replace(listId = listId, generatedBy = list.ownerId, entries = entries)
        }

    fun get(
        token: VerifiedFirebaseToken,
        listId: UUID,
    ): Either<ServiceError, Seeding> =
        either {
            lists.get(token = token, listId = listId).bind() // ownership + access
            seedings.findByListId(listId = listId).bind()
        }
}
