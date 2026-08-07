// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.seeding

import arrow.core.Either
import arrow.core.raise.either
import org.skopeo.common.dto.seeding.SeedingResponse
import org.skopeo.common.error.ServiceError
import org.skopeo.domain.mapper.dto.seeding.toResponse
import org.skopeo.domain.mapper.entity.seeding.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.SeedingEntry
import org.skopeo.domain.model.User
import org.skopeo.domain.model.ageInYears
import org.skopeo.domain.service.event.EventService
import org.skopeo.domain.service.rating.RatingAssembler
import org.skopeo.domain.service.user.UserService
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.domain.service.user.displayName
import org.skopeo.domain.service.user.isDeleted
import org.skopeo.repository.SeedingRepository
import org.skopeo.repository.UserRepository
import java.time.LocalDate
import java.util.UUID
import kotlin.math.ceil

/**
 * Generates the seeding for a source roster: a player list (issue #111) or an event's participants
 * (#714). Only members that have a rating are seeded; they are ordered highest-first (rating →
 * confidence → matches → name) and the top ⌈N/2⌉ get bracket seeds 1..⌈N/2⌉, the rest left blank.
 * Regenerating overwrites the source's previous seeding. The list and event paths share the exact same
 * deterministic sort + snapshot mapping ([buildEntries]), so their output never diverges.
 */
class SeedingService(
    private val lists: PlayerListService = PlayerListService(),
    private val events: EventService = EventService(),
    private val users: UserRepository = UserRepository(),
    private val ratings: RatingAssembler = RatingAssembler(),
    private val seedings: SeedingRepository = SeedingRepository(),
    private val userService: UserService = UserService(),
) {
    fun generate(
        token: VerifiedFirebaseToken,
        listId: UUID,
    ): Either<ServiceError, SeedingResponse> =
        either {
            // Raw rating is ADMINISTRATOR-only (#583): non-admin staff get the band + seed order only.
            val showRaw = userService.callerCanSeeRawRating(token = token)
            val list = lists.get(token = token, listId = listId).bind() // ownership + access
            val entries = buildEntries(memberIds = list.memberUserIds)
            seedings.replace(listId = listId, generatedBy = list.ownerId, entries = entries).toResponse(showRawRating = showRaw)
        }

    fun get(
        token: VerifiedFirebaseToken,
        listId: UUID,
    ): Either<ServiceError, SeedingResponse> =
        either {
            val showRaw = userService.callerCanSeeRawRating(token = token)
            lists.get(token = token, listId = listId).bind() // ownership + access
            seedings.findByListId(listId = listId).bind().toDomain().toResponse(showRawRating = showRaw)
        }

    /**
     * Generate (or regenerate) the seeding for an event's APPROVED participants (#714). Reuses the
     * exact list-path sort + snapshot mapping ([buildEntries]); the event access (staff + owner-or-admin)
     * is enforced by [EventService.rosterForSeeding]. Seeds by player even for doubles events — a known
     * limitation (no doubles pairing logic yet).
     */
    fun generateForEvent(
        token: VerifiedFirebaseToken,
        eventId: UUID,
    ): Either<ServiceError, SeedingResponse> =
        either {
            val showRaw = userService.callerCanSeeRawRating(token = token)
            val roster = events.rosterForSeeding(token = token, id = eventId).bind()
            val entries = buildEntries(memberIds = roster.participantUserIds)
            seedings.replaceForEvent(eventId = eventId, generatedBy = roster.generatedBy, entries = entries)
                .toResponse(showRawRating = showRaw)
        }

    fun getForEvent(
        token: VerifiedFirebaseToken,
        eventId: UUID,
    ): Either<ServiceError, SeedingResponse> =
        either {
            val showRaw = userService.callerCanSeeRawRating(token = token)
            events.rosterForSeeding(token = token, id = eventId).bind() // access check
            seedings.findByEventId(eventId = eventId).bind().toDomain().toResponse(showRawRating = showRaw)
        }

    /**
     * The shared, deterministic seeding computation for both sources: resolve [memberIds] to rated
     * users, order them highest-first with the tie-break chain (rating → confidence → matches → name)
     * in one comparator, and snapshot the top ⌈N/2⌉ as seeds. Only rated members are seeded.
     */
    private fun buildEntries(memberIds: List<UUID>): List<SeedingEntry> {
        val members = users.findAllByIds(ids = memberIds).map { it.toDomain() }
        val currentRatings = ratings.findCurrentRatings(userIds = memberIds)
        val today = LocalDate.now()

        val ordered =
            members
                .mapNotNull { user -> currentRatings[user.id]?.let { user to it } }
                .sortedWith(
                    comparator = { (leftUser, leftRating), (rightUser, rightRating) ->
                        var order = rightRating.currentRating.compareTo(other = leftRating.currentRating)
                        if (order == 0) order = rightRating.confidence.compareTo(other = leftRating.confidence)
                        if (order == 0) order = rightRating.matchesPlayed.compareTo(other = leftRating.matchesPlayed)
                        if (order == 0) order = seedName(user = leftUser).compareTo(other = seedName(user = rightUser))
                        order
                    },
                )

        val seededCount = ceil(x = ordered.size / 2.0).toInt()
        return ordered.mapIndexed { index, (user, rating) ->
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
                placeholder = user.placeholder,
                deleted = user.isDeleted(),
            )
        }
    }

    /** The name to sort and snapshot by: the display name, falling back to the shareable code. */
    private fun seedName(user: User): String = user.displayName() ?: user.publicCode
}
