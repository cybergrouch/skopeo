// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.user

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.dto.user.CreateUserRequest
import org.skopeo.common.error.ServiceError
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import java.util.UUID

/**
 * Who may look a player up (#867) — the `PLAYER_SEARCH_ROLES` gate on `search` and `findByIds`.
 *
 * Its own class rather than more cases in `UserServiceTest`, which is at detekt's `LargeClass` ceiling —
 * and this is a single, self-contained question anyway.
 *
 * The gap being closed: a CLUB_OWNER without HOST could reach the New Event form and the event manager
 * (#789), both of which render a player picker calling these — so the UI was granted while the call it
 * depends on answered 403.
 */
class PlayerSearchAccessTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val repository = UserRepository()
    private val service = UserService(repository = repository)

    private val request =
        CreateUserRequest(proposedRating = "4.0", displayName = "Juan", dateOfBirth = "2000-01-01", sex = "Male")

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid.asRedactable())

    /** Provision [uid] holding PLAYER plus exactly [roles] — the point being which roles it does NOT hold. */
    private fun provisionWith(
        uid: String,
        roles: Array<Capability> = emptyArray(),
    ) = repository
        .provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid.asRedactable(),
                    identity =
                        UserIdentity(provider = org.skopeo.domain.model.AuthProvider.GOOGLE, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = org.skopeo.domain.model.NameType.DISPLAY, value = uid)),
                    capabilities = setOf(element = Capability.PLAYER) + roles,
                ),
        ).toDomain()

    @Test
    fun `a CLUB_OWNER without HOST can search players and resolve them by id (#867)`() {
        // THE account shape that was broken: #789 gives a named club owner the New Event form and the
        // event manager, both of which render a player picker calling search — and CLUB_OWNER was absent
        // from the gate, so the UI was granted while the call it depends on answered 403. Nothing implies
        // HOST from CLUB_OWNER: adding a club owner is an ADMINISTRATOR action granting CLUB_OWNER alone.
        provisionWith(uid = "owner", roles = arrayOf(Capability.CLUB_OWNER))
        val member = service.provision(token = token(uid = "m-owner"), request = request).shouldBeRight().user

        service
            .search(token = token(uid = "owner"), filters = UserSearchFilters(code = member.publicCode))
            .shouldBeRight()
            .single()
            .publicCode shouldBe member.publicCode
        // And id-resolution too, which the picker's roster round-trip needs — it was strictly narrower
        // than search before #867 for no stated reason.
        service
            .findByIds(token = token(uid = "owner"), ids = listOf(element = UUID.fromString(member.id)))
            .shouldBeRight()
            .single()
            .publicCode shouldBe member.publicCode
    }

    @Test
    fun `every player-search role can search and resolve, and a plain player still cannot (#867)`() {
        val member = service.provision(token = token(uid = "m-roles"), request = request).shouldBeRight().user

        // All six of PLAYER_SEARCH_ROLES, so a future narrowing cannot silently drop one. POINTS_MANAGER
        // is in because the Points Management tab grants points to a player it has to find first.
        listOf(
            Capability.HOST,
            Capability.CLUB_OWNER,
            Capability.ADMINISTRATOR,
            Capability.POINTS_MANAGER,
            Capability.RATER,
            Capability.RESEARCHER,
        ).forEach { role ->
            provisionWith(uid = "search-$role", roles = arrayOf(role))
            withClue(clue = "$role should be able to look a player up") {
                service
                    .search(token = token(uid = "search-$role"), filters = UserSearchFilters(code = member.publicCode))
                    .shouldBeRight()
                    .shouldHaveSize(size = 1)
                service
                    .findByIds(token = token(uid = "search-$role"), ids = listOf(element = UUID.fromString(member.id)))
                    .shouldBeRight()
                    .shouldHaveSize(size = 1)
            }
        }

        // Widening the set is not the same as opening it: a plain player still cannot enumerate others.
        provisionWith(uid = "just-a-player")
        service
            .search(token = token(uid = "just-a-player"), filters = UserSearchFilters(code = member.publicCode))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .findByIds(token = token(uid = "just-a-player"), ids = listOf(element = UUID.fromString(member.id)))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }
}
