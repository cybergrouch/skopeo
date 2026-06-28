// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.seeding

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import org.skopeo.dto.seeding.PlayerListResponse
import org.skopeo.dto.user.toSummary
import org.skopeo.model.Capability
import org.skopeo.model.PlayerList
import org.skopeo.model.ServiceError
import org.skopeo.repository.PlayerListRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import java.util.UUID

/** Roles that may build seeding lists (#111): HOST and CLUB_OWNER, plus ADMINISTRATOR. */
internal val SEEDING_ROLES = setOf(Capability.HOST, Capability.CLUB_OWNER, Capability.ADMINISTRATOR)

/**
 * Host-curated player lists (issue #111) — HOST/CLUB_OWNER/ADMINISTRATOR only. Each list is owned by
 * its creator; a caller only sees and manages their own lists. Members must be PLAYER-capable users.
 * Expected failures are returned as an [Either] left ([ServiceError]).
 */
class PlayerListService(
    private val lists: PlayerListRepository = PlayerListRepository(),
    private val users: UserRepository = UserRepository(),
    private val ratings: RatingRepository = RatingRepository(),
) {
    fun create(
        token: VerifiedFirebaseToken,
        name: String,
    ): Either<ServiceError, PlayerList> =
        either {
            val ownerId = requireSeeder(token = token).bind()
            ensure(condition = name.isNotBlank()) { ServiceError.Validation(message = "A list name is required") }
            lists.create(ownerId = ownerId, name = name.trim())
        }

    fun listMine(token: VerifiedFirebaseToken): Either<ServiceError, List<PlayerList>> =
        either {
            val ownerId = requireSeeder(token = token).bind()
            lists.listByOwner(ownerId = ownerId)
        }

    /** A single owned list (with members). Surfaces [ServiceError.Forbidden] for someone else's list. */
    fun get(
        token: VerifiedFirebaseToken,
        listId: UUID,
    ): Either<ServiceError, PlayerList> =
        either {
            val ownerId = requireSeeder(token = token).bind()
            val list = lists.findById(id = listId).bind()
            ensure(condition = list.ownerId == ownerId) { ServiceError.Forbidden() }
            list
        }

    /** An owned list with its members resolved to summaries (each carrying their current band). */
    fun detail(
        token: VerifiedFirebaseToken,
        listId: UUID,
    ): Either<ServiceError, PlayerListResponse> =
        either {
            val list = get(token = token, listId = listId).bind()
            val members = users.findAllByIds(ids = list.memberUserIds)
            val currentRatings = ratings.findCurrentRatings(userIds = list.memberUserIds)
            PlayerListResponse(
                id = list.id.toString(),
                name = list.name,
                createdAt = list.createdAt.toString(),
                members = members.map { it.toSummary(rating = currentRatings[it.id]) },
            )
        }

    fun delete(
        token: VerifiedFirebaseToken,
        listId: UUID,
    ): Either<ServiceError, Unit> =
        either {
            get(token = token, listId = listId).bind() // ownership + existence
            lists.delete(id = listId)
        }

    fun addMember(
        token: VerifiedFirebaseToken,
        listId: UUID,
        userId: UUID,
    ): Either<ServiceError, Unit> =
        either {
            get(token = token, listId = listId).bind()
            val member = users.findById(id = userId).bind()
            ensure(condition = member.capabilities.contains(element = Capability.PLAYER)) {
                ServiceError.Validation(message = "Only players can be added to a list")
            }
            lists.addMember(listId = listId, userId = userId).bind()
        }

    fun removeMember(
        token: VerifiedFirebaseToken,
        listId: UUID,
        userId: UUID,
    ): Either<ServiceError, Unit> =
        either {
            get(token = token, listId = listId).bind()
            lists.removeMember(listId = listId, userId = userId).bind()
        }

    private fun requireSeeder(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid) ?: return ServiceError.Forbidden().left()
        return if (caller.capabilities.any { it in SEEDING_ROLES }) caller.id.right() else ServiceError.Forbidden().left()
    }
}
