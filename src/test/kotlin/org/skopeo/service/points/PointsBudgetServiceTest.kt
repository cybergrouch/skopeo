// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.points

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.CreateClubCommand
import org.skopeo.model.EventType
import org.skopeo.model.NameType
import org.skopeo.model.PointsPolicy
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.ClubRepository
import org.skopeo.repository.PointsBudgetRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.util.UUID

class PointsBudgetServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val clubs = ClubRepository()
    private val service = PointsBudgetService(users = users, clubs = clubs, budgets = PointsBudgetRepository())

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun provision(
        uid: String,
        roles: Set<Capability> = setOf(element = Capability.PLAYER),
    ): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = roles,
                ),
        )

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private fun newClub(ownerId: UUID): UUID = clubs.create(command = CreateClubCommand(name = "Club", createdBy = ownerId)).id

    private fun policy(
        type: EventType,
        min: Int,
        max: Int,
        days: Int = 30,
    ) = PointsPolicy(eventType = type, minPoints = min, maxPoints = max, maxValidityDays = days)

    @Test
    fun `a points manager reads and sets a policy (#403)`() {
        provision(uid = "pm", roles = setOf(Capability.PLAYER, Capability.POINTS_MANAGER))

        service.policies(token = token(uid = "pm")).shouldBeRight() shouldHaveSize EventType.entries.size

        val saved =
            service.setPolicy(token = token(uid = "pm"), policy = policy(type = EventType.LEAGUE, min = 3, max = 40, days = 60))
                .shouldBeRight()
        saved.minPoints shouldBe 3
        saved.maxPoints shouldBe 40
        saved.maxValidityDays shouldBe 60
    }

    @Test
    fun `an administrator is implicitly a points manager (#403)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        service.policies(token = token(uid = "admin")).shouldBeRight()
        service.setPolicy(token = token(uid = "admin"), policy = policy(type = EventType.OPEN_PLAY, min = 1, max = 5))
            .shouldBeRight()
    }

    @Test
    fun `a plain player and an unprovisioned caller are forbidden (#403)`() {
        provision(uid = "player")
        service.policies(token = token(uid = "player")).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.policies(token = token(uid = "ghost")).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.setPolicy(token = token(uid = "player"), policy = policy(type = EventType.OPEN_PLAY, min = 1, max = 5))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `setPolicy rejects min greater than max and non-positive values (#403)`() {
        provision(uid = "pm", roles = setOf(Capability.PLAYER, Capability.POINTS_MANAGER))
        service.setPolicy(token = token(uid = "pm"), policy = policy(type = EventType.OPEN_PLAY, min = 10, max = 5))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.setPolicy(token = token(uid = "pm"), policy = policy(type = EventType.OPEN_PLAY, min = 0, max = 5))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.setPolicy(token = token(uid = "pm"), policy = policy(type = EventType.OPEN_PLAY, min = 1, max = 0))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.setPolicy(token = token(uid = "pm"), policy = policy(type = EventType.OPEN_PLAY, min = 1, max = 5, days = 0))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `clubBudgets surfaces every type with budgeted, allocated zero and free equal to budgeted (#403)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val club = newClub(ownerId = admin.id)
        service.setClubBudget(token = token(uid = "admin"), clubId = club, eventType = EventType.LEAGUE, points = 200)
            .shouldBeRight()

        val views = service.clubBudgets(token = token(uid = "admin"), clubId = club).shouldBeRight()
        // One row per event type, even the ones with no budget set (default 0).
        views shouldHaveSize EventType.entries.size
        val league = views.single { it.eventType == EventType.LEAGUE }
        league.budgeted shouldBe 200
        league.allocated shouldBe 0
        league.free shouldBe 200
        val openPlay = views.single { it.eventType == EventType.OPEN_PLAY }
        openPlay.budgeted shouldBe 0
        openPlay.free shouldBe 0
    }

    @Test
    fun `clubBudgets with no club id spans all clubs (#403)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val clubA = newClub(ownerId = admin.id)
        val clubB = newClub(ownerId = admin.id)

        val views = service.clubBudgets(token = token(uid = "admin"), clubId = null).shouldBeRight()
        views.map { it.clubId }.toSet() shouldBe setOf(clubA, clubB)
        views shouldHaveSize EventType.entries.size * 2
    }

    @Test
    fun `setClubBudget rejects a negative budget and an unknown club (#403)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val club = newClub(ownerId = admin.id)

        service.setClubBudget(token = token(uid = "admin"), clubId = club, eventType = EventType.OPEN_PLAY, points = -1)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.setClubBudget(
            token = token(uid = "admin"),
            clubId = UUID.randomUUID(),
            eventType = EventType.OPEN_PLAY,
            points = 10,
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `clubBudgets is not-found for an unknown club and forbidden for a plain player (#403)`() {
        provision(uid = "player")
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val club = newClub(ownerId = admin.id)

        service.clubBudgets(token = token(uid = "admin"), clubId = UUID.randomUUID())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
        service.clubBudgets(token = token(uid = "player"), clubId = club)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.setClubBudget(token = token(uid = "player"), clubId = club, eventType = EventType.OPEN_PLAY, points = 1)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }
}
