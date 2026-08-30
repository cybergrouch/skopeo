// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldHaveLength
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.redaction.asRedactable
import org.skopeo.domain.mapper.entity.club.toDomain
import org.skopeo.domain.mapper.entity.event.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateClubCommand
import org.skopeo.domain.model.CreateEventCommand
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.testsupport.PostgresTestDatabase
import java.sql.SQLException
import java.time.LocalDate
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
    private val events = EventRepository()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun newUser(uid: String): UUID =
        users.provision(
            command =
                ProvisionUserCommand(
                    firebaseUid = uid.asRedactable(),
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                ),
        ).toDomain().id

    @Test
    fun `create round-trips and findById is null for a missing club`() {
        val admin = newUser(uid = "admin")
        val club = clubs.create(command = CreateClubCommand(name = "Downtown", createdBy = admin)).toDomain()

        clubs.findById(id = club.id)!!.toDomain().let {
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
        val club = clubs.create(command = CreateClubCommand(name = "West End", createdBy = admin)).toDomain()

        clubs.addOwner(clubId = club.id, userId = owner)!!.toDomain().ownerIds shouldBe listOf(element = owner)
        // Re-adding is idempotent (the already-owner branch).
        clubs.addOwner(clubId = club.id, userId = owner)!!.toDomain().ownerIds shouldHaveSize 1
        // Removing a non-owner is a no-op.
        clubs.removeOwner(clubId = club.id, userId = UUID.randomUUID())!!.toDomain().ownerIds shouldHaveSize 1
        clubs.removeOwner(clubId = club.id, userId = owner)!!.toDomain().ownerIds shouldHaveSize 0

        // A missing club yields null from either mutation.
        clubs.addOwner(clubId = UUID.randomUUID(), userId = owner).shouldBeNull()
        clubs.removeOwner(clubId = UUID.randomUUID(), userId = owner).shouldBeNull()
    }

    @Test
    fun `create generates a unique public code and findByPublicCode round-trips it (#327)`() {
        val admin = newUser(uid = "admin")
        val a = clubs.create(command = CreateClubCommand(name = "A", createdBy = admin)).toDomain()
        val b = clubs.create(command = CreateClubCommand(name = "B", createdBy = admin)).toDomain()

        a.publicCode shouldHaveLength 6
        // Each club gets its own code.
        (a.publicCode == b.publicCode) shouldBe false

        // findByPublicCode resolves the right club, and is null for an unknown code.
        clubs.findByPublicCode(code = a.publicCode).shouldNotBeNull().toDomain().id shouldBe a.id
        clubs.findByPublicCode(code = "ZZZZZZ").shouldBeNull()
    }

    @Test
    fun `a club whose creator was deleted loads with a null creator`() {
        val admin = newUser(uid = "admin")
        val club = clubs.create(command = CreateClubCommand(name = "Orphan", createdBy = admin)).toDomain()

        // Hard-delete the creator; the clubs.created_by FK is ON DELETE SET NULL.
        transaction { UsersTable.deleteWhere { UsersTable.id eq admin } }

        clubs.findById(id = club.id)!!.toDomain().createdBy.shouldBeNull()
    }

    @Test
    fun `a hard club delete with dependent events is refused, not turned into a null club_id (#800)`() {
        val admin = newUser(uid = "admin")
        val club = clubs.create(command = CreateClubCommand(name = "Riverside", createdBy = admin)).toDomain()
        val event =
            events.create(
                command =
                    CreateEventCommand(
                        clubId = club.id,
                        name = "Riverside Open",
                        startDate = LocalDate.parse("2026-06-01"),
                        endDate = LocalDate.parse("2026-06-02"),
                        participantIds = emptyList(),
                        createdBy = admin,
                    ),
            ).toDomain()

        // No service ever hard-deletes a club — ClubService.disable soft-deletes it and its events — so
        // reach past the service and delete the row, the way a console or a cleanup script would.
        val failure =
            shouldThrow<ExposedSQLException> {
                transaction { ClubsTable.deleteWhere { ClubsTable.id eq club.id } }
            }

        // 23503 foreign_key_violation raised on `clubs`: the delete itself is refused, and names the
        // dependent events. Before V45 (#800) the FK was ON DELETE SET NULL against a column V44 had made
        // NOT NULL, so the same delete instead surfaced as 23502 (not_null_violation) on `events` — a
        // failure on the wrong table that says nothing about the club being undeletable.
        (failure.cause as? SQLException)?.sqlState shouldBe "23503"
        failure.cause?.message.shouldNotBeNull() shouldContain "events_club_id_fkey"

        // Nothing was destroyed or blanked by the attempt.
        clubs.findById(id = club.id).shouldNotBeNull()
        events.findById(id = event.id).shouldNotBeNull().toDomain().clubId shouldBe club.id
    }
}
