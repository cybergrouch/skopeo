// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.CreateEventCommand
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.testsupport.PostgresTestDatabase
import java.time.LocalDate
import java.util.UUID

class EventRepositoryTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val events = EventRepository()

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
    fun `an event whose creator was removed (created_by SET NULL) still loads`() {
        val creator = newUser(uid = "creator")
        val event =
            events.create(
                command =
                    CreateEventCommand(
                        name = "Orphan Cup",
                        startDate = LocalDate.parse("2026-04-01"),
                        endDate = LocalDate.parse("2026-04-02"),
                        participantIds = emptyList(),
                        createdBy = creator,
                    ),
            )
        event.createdBy shouldBe creator

        // Simulate the creator's account being removed — the FK is ON DELETE SET NULL.
        transaction { EventsTable.update(where = { EventsTable.id eq event.id }) { it[createdBy] = null } }

        events.findById(id = event.id)!!.createdBy.shouldBeNull()
    }

    @Test
    fun `findByPublicCode resolves an event even once disabled, for traceability (#325)`() {
        val creator = newUser(uid = "creator")
        val event =
            events.create(
                command =
                    CreateEventCommand(
                        name = "Code Cup",
                        startDate = LocalDate.parse("2026-05-01"),
                        endDate = LocalDate.parse("2026-05-02"),
                        participantIds = emptyList(),
                        createdBy = creator,
                    ),
            )
        events.findByPublicCode(code = event.publicCode)!!.id shouldBe event.id

        // A disabled (soft-deleted) event still resolves by code so its link stays honored (#325);
        // it's simply flagged not-active.
        transaction { EventsTable.update(where = { EventsTable.id eq event.id }) { it[isActive] = false } }
        events.findByPublicCode(code = event.publicCode)!!.isActive shouldBe false
    }

    @Test
    fun `rename updates the name and returns the event, or null when absent`() {
        val creator = newUser(uid = "creator")
        val event =
            events.create(
                command =
                    CreateEventCommand(
                        name = "Old Name",
                        startDate = LocalDate.parse("2026-06-01"),
                        endDate = LocalDate.parse("2026-06-02"),
                        participantIds = emptyList(),
                        createdBy = creator,
                    ),
            )

        events.rename(id = event.id, name = "New Name")!!.name shouldBe "New Name"
        events.findById(id = event.id)!!.name shouldBe "New Name"
        events.rename(id = UUID.randomUUID(), name = "Ghost").shouldBeNull()
    }

    @Test
    fun `setCalcPriority stores the override and a missing id is a harmless no-op (#335)`() {
        val creator = newUser(uid = "creator")
        val event =
            events.create(
                command =
                    CreateEventCommand(
                        name = "Priority Cup",
                        startDate = LocalDate.parse("2026-07-01"),
                        endDate = LocalDate.parse("2026-07-02"),
                        participantIds = emptyList(),
                        createdBy = creator,
                    ),
            )
        // No override to start with.
        events.findById(id = event.id)!!.calcPriority.shouldBeNull()

        events.setCalcPriority(id = event.id, priority = 3.5)
        events.findById(id = event.id)!!.calcPriority shouldBe 3.5

        // Updating a missing id matches no rows and must not throw or touch the real event.
        events.setCalcPriority(id = UUID.randomUUID(), priority = 9.0)
        events.findById(id = event.id)!!.calcPriority shouldBe 3.5
    }
}
