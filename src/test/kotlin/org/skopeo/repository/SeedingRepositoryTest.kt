// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.error.ServiceError
import org.skopeo.domain.mapper.entity.seeding.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.SeedingEntry
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.testsupport.PostgresTestDatabase

class SeedingRepositoryTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val lists = PlayerListRepository()
    private val seedings = SeedingRepository()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun newUser(uid: String): User =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid,
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                ),
        ).toDomain()

    private fun entryFor(
        user: User,
        displayName: String?,
    ) = SeedingEntry(
        seed = 1,
        position = 1,
        userId = user.id,
        displayName = displayName,
        publicCode = "ABC123",
        ntrpBand = "4.0",
        rating = "4.000000",
        sex = "Male",
        age = 30,
    )

    @Test
    fun `replace stores entries and findByListId reads them back`() {
        val owner = newUser(uid = "owner")
        val list = lists.create(ownerId = owner.id, name = "L").toDomain()
        val player = newUser(uid = "p1")

        seedings.replace(listId = list.id, generatedBy = owner.id, entries = listOf(element = entryFor(user = player, displayName = "P1")))

        val found = seedings.findByListId(listId = list.id).shouldBeRight().toDomain()
        found.entries.single().let {
            it.userId shouldBe player.id
            it.displayName shouldBe "P1"
        }
    }

    @Test
    fun `findByListId is not found when no seeding exists`() {
        val owner = newUser(uid = "owner")
        val list = lists.create(ownerId = owner.id, name = "Empty").toDomain()
        seedings.findByListId(listId = list.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `a deleted user's entry keeps its snapshot but drops the user link (SET NULL)`() {
        val owner = newUser(uid = "owner")
        val list = lists.create(ownerId = owner.id, name = "L").toDomain()
        val victim = newUser(uid = "victim")
        seedings.replace(
            listId = list.id,
            generatedBy = owner.id,
            entries = listOf(element = entryFor(user = victim, displayName = "Victim")),
        )

        // Hard-delete the user → the FK (ON DELETE SET NULL) nulls the entry's user_id; the snapshot stays.
        transaction { UsersTable.deleteWhere { UsersTable.id eq victim.id } }

        seedings.findByListId(listId = list.id).shouldBeRight().toDomain().entries.single().let {
            it.userId.shouldBeNull()
            it.displayName shouldBe "Victim"
            it.publicCode shouldBe "ABC123"
        }
    }
}
