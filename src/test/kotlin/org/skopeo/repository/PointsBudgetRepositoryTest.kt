// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.CreateClubCommand
import org.skopeo.model.EventType
import org.skopeo.model.NameType
import org.skopeo.model.PointsPolicy
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.testsupport.PostgresTestDatabase
import java.util.UUID

class PointsBudgetRepositoryTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val clubs = ClubRepository()
    private val repo = PointsBudgetRepository()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun newUser(uid: String): UUID =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = emptySet(),
                ),
        ).id

    @Test
    fun `listPolicies returns the seeded policy per event type (#403)`() {
        val policies = repo.listPolicies()
        policies shouldHaveSize EventType.entries.size
        policies.map { it.eventType }.toSet() shouldBe EventType.entries.toSet()
    }

    @Test
    fun `findPolicy reads one type and upsertPolicy overwrites it in place (#403)`() {
        repo.findPolicy(eventType = EventType.LEAGUE).shouldNotBeNull().minPoints shouldBe 5

        val updated =
            repo.upsertPolicy(
                policy = PointsPolicy(eventType = EventType.LEAGUE, minPoints = 7, maxPoints = 99, maxValidityDays = 120),
            )
        updated.minPoints shouldBe 7
        updated.maxPoints shouldBe 99
        updated.maxValidityDays shouldBe 120

        repo.findPolicy(eventType = EventType.LEAGUE).shouldNotBeNull().maxPoints shouldBe 99
        // Upsert is in place — still one row per type.
        repo.listPolicies() shouldHaveSize EventType.entries.size
    }

    @Test
    fun `upsertBudget inserts then updates a club × type row, keyed by the pair (#403)`() {
        val admin = newUser(uid = "admin")
        val club = clubs.create(command = CreateClubCommand(name = "Club", createdBy = admin)).id

        repo.listBudgetsForClub(clubId = club) shouldHaveSize 0

        val inserted =
            repo.upsertBudget(clubId = club, eventType = EventType.OPEN_PLAY, budgetedPoints = 100, updatedBy = admin)
        inserted.budgetedPoints shouldBe 100
        repo.listBudgetsForClub(clubId = club) shouldHaveSize 1

        // Same (club, type) updates in place.
        val updated =
            repo.upsertBudget(clubId = club, eventType = EventType.OPEN_PLAY, budgetedPoints = 250, updatedBy = admin)
        updated.budgetedPoints shouldBe 250
        repo.listBudgetsForClub(clubId = club) shouldHaveSize 1

        // A different type is a separate row under the same PK space.
        repo.upsertBudget(clubId = club, eventType = EventType.TOURNAMENT, budgetedPoints = 500, updatedBy = admin)
        repo.listBudgetsForClub(clubId = club) shouldHaveSize 2
    }

    @Test
    fun `listBudgets spans all clubs (#403)`() {
        val admin = newUser(uid = "admin")
        val clubA = clubs.create(command = CreateClubCommand(name = "A", createdBy = admin)).id
        val clubB = clubs.create(command = CreateClubCommand(name = "B", createdBy = admin)).id
        repo.upsertBudget(clubId = clubA, eventType = EventType.LEAGUE, budgetedPoints = 10, updatedBy = admin)
        repo.upsertBudget(clubId = clubB, eventType = EventType.LEAGUE, budgetedPoints = 20, updatedBy = admin)

        repo.listBudgets() shouldHaveSize 2
    }

    @Test
    fun `upsertPolicy inserts a policy for a type that has no row (#403)`() {
        // The seed gives every type a row; delete one so upsert takes the insert (not update) branch.
        org.jetbrains.exposed.sql.transactions.transaction {
            exec(stmt = "DELETE FROM points_policies WHERE event_type = 'TOURNAMENT'")
        }
        repo.findPolicy(eventType = EventType.TOURNAMENT).shouldBeNull()

        val inserted =
            repo.upsertPolicy(
                policy = PointsPolicy(eventType = EventType.TOURNAMENT, minPoints = 2, maxPoints = 20, maxValidityDays = 200),
            )
        inserted.minPoints shouldBe 2
        repo.findPolicy(eventType = EventType.TOURNAMENT).shouldNotBeNull().maxValidityDays shouldBe 200
    }

    @Test
    fun `findPolicy is null for an event type with no policy row (#403)`() {
        // Truncate wipes and reseeds all three types; delete one to hit the null-return path.
        org.jetbrains.exposed.sql.transactions.transaction {
            exec(stmt = "DELETE FROM points_policies WHERE event_type = 'TOURNAMENT'")
        }
        repo.findPolicy(eventType = EventType.TOURNAMENT).shouldBeNull()
    }

    @Test
    fun `deleting a club cascades away its budgets (#403)`() {
        val admin = newUser(uid = "admin")
        val club = clubs.create(command = CreateClubCommand(name = "Doomed", createdBy = admin)).id
        repo.upsertBudget(clubId = club, eventType = EventType.OPEN_PLAY, budgetedPoints = 10, updatedBy = admin)

        org.jetbrains.exposed.sql.transactions.transaction {
            exec(stmt = "DELETE FROM clubs WHERE id = '$club'")
        }
        repo.listBudgetsForClub(clubId = club) shouldHaveSize 0
    }
}
