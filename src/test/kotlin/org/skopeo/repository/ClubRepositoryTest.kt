// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.CreateClubCommand
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.testsupport.PostgresTestDatabase
import java.util.UUID

class ClubRepositoryTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val clubs = ClubRepository()

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
                ),
        ).id

    @Test
    fun `create round-trips and findById is null for a missing club`() {
        val admin = newUser(uid = "admin")
        val club = clubs.create(command = CreateClubCommand(name = "Downtown", createdBy = admin))

        clubs.findById(id = club.id)!!.let {
            it.name shouldBe "Downtown"
            it.createdBy shouldBe admin
            it.ownerIds shouldHaveSize 0
        }
        clubs.findById(id = UUID.randomUUID()).shouldBeNull()
    }

    @Test
    fun `addOwner is idempotent, removeOwner is a no-op when absent, and both are null for a missing club`() {
        val admin = newUser(uid = "admin")
        val owner = newUser(uid = "owner")
        val club = clubs.create(command = CreateClubCommand(name = "West End", createdBy = admin))

        clubs.addOwner(clubId = club.id, userId = owner)!!.ownerIds shouldBe listOf(element = owner)
        // Re-adding is idempotent (the already-owner branch).
        clubs.addOwner(clubId = club.id, userId = owner)!!.ownerIds shouldHaveSize 1
        // Removing a non-owner is a no-op.
        clubs.removeOwner(clubId = club.id, userId = UUID.randomUUID())!!.ownerIds shouldHaveSize 1
        clubs.removeOwner(clubId = club.id, userId = owner)!!.ownerIds shouldHaveSize 0

        // A missing club yields null from either mutation.
        clubs.addOwner(clubId = UUID.randomUUID(), userId = owner).shouldBeNull()
        clubs.removeOwner(clubId = UUID.randomUUID(), userId = owner).shouldBeNull()
    }

    @Test
    fun `a club whose creator was deleted loads with a null creator`() {
        val admin = newUser(uid = "admin")
        val club = clubs.create(command = CreateClubCommand(name = "Orphan", createdBy = admin))

        // Hard-delete the creator; the clubs.created_by FK is ON DELETE SET NULL.
        transaction { UsersTable.deleteWhere { UsersTable.id eq admin } }

        clubs.findById(id = club.id)!!.createdBy.shouldBeNull()
    }
}
