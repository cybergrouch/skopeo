// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.redaction.asRedactable
import org.skopeo.domain.mapper.entity.event.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateEventCommand
import org.skopeo.domain.model.EventParticipantStatus
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.seedClub
import java.time.LocalDate
import java.time.LocalDateTime
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
                    firebaseUid = uid.asRedactable(),
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                ),
        ).toDomain().id

    @Test
    fun `an event whose creator was removed (created_by SET NULL) still loads`() {
        val creator = newUser(uid = "creator")
        val event =
            events.create(
                command =
                    CreateEventCommand(
                        // Every event needs a club (#794).
                        clubId = seedClub().id,
                        name = "Orphan Cup",
                        startDate = LocalDate.parse("2026-04-01"),
                        endDate = LocalDate.parse("2026-04-02"),
                        participantIds = emptyList(),
                        createdBy = creator,
                    ),
            ).toDomain()
        event.createdBy shouldBe creator

        // Simulate the creator's account being removed — the FK is ON DELETE SET NULL.
        transaction { EventsTable.update(where = { EventsTable.id eq event.id }) { it[createdBy] = null } }

        events.findById(id = event.id)!!.toDomain().createdBy.shouldBeNull()
    }

    @Test
    fun `findByPublicCode resolves an event even once disabled, for traceability (#325)`() {
        val creator = newUser(uid = "creator")
        val event =
            events.create(
                command =
                    CreateEventCommand(
                        // Every event needs a club (#794).
                        clubId = seedClub().id,
                        name = "Code Cup",
                        startDate = LocalDate.parse("2026-05-01"),
                        endDate = LocalDate.parse("2026-05-02"),
                        participantIds = emptyList(),
                        createdBy = creator,
                    ),
            ).toDomain()
        events.findByPublicCode(code = event.publicCode)!!.toDomain().id shouldBe event.id

        // A disabled (soft-deleted) event still resolves by code so its link stays honored (#325);
        // it's simply flagged not-active.
        transaction { EventsTable.update(where = { EventsTable.id eq event.id }) { it[isActive] = false } }
        events.findByPublicCode(code = event.publicCode)!!.toDomain().isActive shouldBe false
    }

    @Test
    fun `rename updates the name and returns the event, or null when absent`() {
        val creator = newUser(uid = "creator")
        val event =
            events.create(
                command =
                    CreateEventCommand(
                        // Every event needs a club (#794).
                        clubId = seedClub().id,
                        name = "Old Name",
                        startDate = LocalDate.parse("2026-06-01"),
                        endDate = LocalDate.parse("2026-06-02"),
                        participantIds = emptyList(),
                        createdBy = creator,
                    ),
            ).toDomain()

        events.rename(id = event.id, name = "New Name")!!.toDomain().name shouldBe "New Name"
        events.findById(id = event.id)!!.toDomain().name shouldBe "New Name"
        events.rename(id = UUID.randomUUID(), name = "Ghost").shouldBeNull()
    }

    @Test
    fun `setCalcPriority stores the override and a missing id is a harmless no-op (#335)`() {
        val creator = newUser(uid = "creator")
        val event =
            events.create(
                command =
                    CreateEventCommand(
                        // Every event needs a club (#794).
                        clubId = seedClub().id,
                        name = "Priority Cup",
                        startDate = LocalDate.parse("2026-07-01"),
                        endDate = LocalDate.parse("2026-07-02"),
                        participantIds = emptyList(),
                        createdBy = creator,
                    ),
            ).toDomain()
        // No override to start with.
        events.findById(id = event.id)!!.toDomain().calcPriority.shouldBeNull()

        events.setCalcPriority(id = event.id, priority = 3.5)
        events.findById(id = event.id)!!.toDomain().calcPriority shouldBe 3.5

        // Updating a missing id matches no rows and must not throw or touch the real event.
        events.setCalcPriority(id = UUID.randomUUID(), priority = 9.0)
        events.findById(id = event.id)!!.toDomain().calcPriority shouldBe 3.5
    }

    @Test
    fun `addParticipant returns null when the event does not exist (#201)`() {
        val host = newUser(uid = "host")
        val player = newUser(uid = "player")

        events
            .addParticipant(eventId = UUID.randomUUID(), userId = player, approvedBy = host)
            .shouldBeNull()
    }

    @Test
    fun `addParticipant seats a new player as APPROVED and hands back the aggregate already carrying them (#201)`() {
        val host = newUser(uid = "host")
        val player = newUser(uid = "player")
        val eventId = newEvent(creator = host, name = "Host Add Cup")

        // The returned aggregate is re-read after the write, so a caller (EventService → the roster
        // response) never has to reload the event to see who is now on it.
        val updated = events.addParticipant(eventId = eventId, userId = player, approvedBy = host)!!.toDomain()
        updated.participantIds shouldContainExactlyInAnyOrder listOf(element = player)

        val row = participantRows(eventId = eventId, userId = player).single()
        row.status shouldBe EventParticipantStatus.APPROVED.name
        // Who let them in is attributable — the approval is stamped, not anonymous.
        row.approvedBy shouldBe host
        // Postgres TIMESTAMP truncates the nanos off LocalDateTime.now(), so only the presence of the
        // stamp is meaningful to assert here, never an exact instant.
        row.approvedAt.shouldNotBeNull()
        // A host-add was never "requested": requested_at belongs to the self-signup flow alone.
        row.requestedAt.shouldBeNull()
    }

    @Test
    fun `addParticipant promotes a pending self-signup in place rather than seating the player twice (#201)`() {
        val host = newUser(uid = "host")
        val player = newUser(uid = "player")
        val eventId = newEvent(creator = host, name = "Promotion Cup")

        events.selfSignup(eventId = eventId, userId = player)
        val pending = participantRows(eventId = eventId, userId = player).single()
        pending.status shouldBe EventParticipantStatus.PENDING.name

        val promoted = events.addParticipant(eventId = eventId, userId = player, approvedBy = host)!!.toDomain()
        promoted.participantIds shouldContainExactlyInAnyOrder listOf(element = player)

        // The guarantee: approving a request UPDATEs the existing membership. A second row would enter
        // the same player twice into seeding and fixture generation.
        val rows = participantRows(eventId = eventId, userId = player)
        rows shouldHaveSize 1
        val row = rows.single()
        row.status shouldBe EventParticipantStatus.APPROVED.name
        row.approvedBy shouldBe host
        row.approvedAt.shouldNotBeNull()
        // When they originally asked survives the promotion — comparing one stored value against another
        // (both already truncated by the DB) keeps this free of nanosecond flakiness.
        row.requestedAt shouldBe pending.requestedAt
    }

    @Test
    fun `addParticipant is idempotent, a repeated host-add leaves exactly one APPROVED membership (#201)`() {
        val host = newUser(uid = "host")
        val admin = newUser(uid = "admin")
        val player = newUser(uid = "player")
        val eventId = newEvent(creator = host, name = "Repeat Cup")

        events.addParticipant(eventId = eventId, userId = player, approvedBy = host)
        val again = events.addParticipant(eventId = eventId, userId = player, approvedBy = admin)!!.toDomain()

        // Re-adding someone already on the roster is a no-harm retry (double-clicked button, replayed
        // request): still one membership, still APPROVED.
        again.participantIds shouldContainExactlyInAnyOrder listOf(element = player)
        val rows = participantRows(eventId = eventId, userId = player)
        rows shouldHaveSize 1
        val row = rows.single()
        row.status shouldBe EventParticipantStatus.APPROVED.name
        // The re-add takes the update branch, so the most recent approver is the one on record.
        row.approvedBy shouldBe admin
    }

    @Test
    fun `selfSignup files a PENDING request that stays off the APPROVED roster until a host acts (#201)`() {
        val host = newUser(uid = "host")
        val player = newUser(uid = "player")
        val eventId = newEvent(creator = host, name = "Signup Cup")

        val signedUp = events.selfSignup(eventId = eventId, userId = player)!!.toDomain()
        // A request is not membership: the aggregate's roster is APPROVED-only, so a self-signup can't
        // sneak a player into fixtures or seeding before the host reviews it.
        signedUp.participantIds.shouldBeEmpty()

        val row = participantRows(eventId = eventId, userId = player).single()
        row.status shouldBe EventParticipantStatus.PENDING.name
        row.requestedAt.shouldNotBeNull()
        // Nothing has been approved yet, so no approval attribution exists to read.
        row.approvedBy.shouldBeNull()
        row.approvedAt.shouldBeNull()
    }

    @Test
    fun `selfSignup never demotes an already APPROVED participant back to PENDING (#201)`() {
        val host = newUser(uid = "host")
        val player = newUser(uid = "player")
        val eventId = newEvent(creator = host, name = "No Demotion Cup")
        events.addParticipant(eventId = eventId, userId = player, approvedBy = host)
        val approved = participantRows(eventId = eventId, userId = player).single()

        val after = events.selfSignup(eventId = eventId, userId = player)!!.toDomain()

        // The guarantee worth locking down: a stray re-signup (stale tab, retried request) must leave an
        // existing row untouched. Overwriting it would silently drop a rostered player back into the
        // pending queue and out of seeding, with nobody notified.
        after.participantIds shouldContainExactlyInAnyOrder listOf(element = player)
        val rows = participantRows(eventId = eventId, userId = player)
        rows shouldHaveSize 1
        val row = rows.single()
        row.status shouldBe EventParticipantStatus.APPROVED.name
        // The original approval attribution survives intact (stored value vs stored value).
        row.approvedBy shouldBe host
        row.approvedAt shouldBe approved.approvedAt
    }

    @Test
    fun `selfSignup returns null when the event does not exist (#201)`() {
        val player = newUser(uid = "player")

        events.selfSignup(eventId = UUID.randomUUID(), userId = player).shouldBeNull()
        // A signup for a ghost event writes nothing — no orphan membership left behind.
        allParticipantRows().shouldBeEmpty()
    }

    private fun newEvent(
        creator: UUID,
        name: String,
    ): UUID =
        events
            .create(
                command =
                    CreateEventCommand(
                        // Every event needs a club (#794).
                        clubId = seedClub().id,
                        name = name,
                        startDate = LocalDate.parse("2026-08-01"),
                        endDate = LocalDate.parse("2026-08-02"),
                        participantIds = emptyList(),
                        createdBy = creator,
                    ),
            ).toDomain()
            .id

    /**
     * The raw membership rows for one user on one event — read straight from the table because the
     * repository's aggregate deliberately exposes only APPROVED ids, hiding both the pending rows and
     * the approval stamps these tests are pinning. Returned as a list so duplicates are visible.
     */
    private fun participantRows(
        eventId: UUID,
        userId: UUID,
    ): List<ParticipantRow> =
        transaction {
            EventParticipantsTable
                .selectAll()
                .where { (EventParticipantsTable.eventId eq eventId) and (EventParticipantsTable.userId eq userId) }
                .map { it.toParticipantRow() }
        }

    private fun allParticipantRows(): List<ParticipantRow> =
        transaction {
            EventParticipantsTable.selectAll().map { it.toParticipantRow() }
        }
}

private data class ParticipantRow(
    val status: String,
    val requestedAt: LocalDateTime?,
    val approvedBy: UUID?,
    val approvedAt: LocalDateTime?,
)

private fun ResultRow.toParticipantRow(): ParticipantRow =
    ParticipantRow(
        status = this[EventParticipantsTable.status],
        requestedAt = this[EventParticipantsTable.requestedAt],
        approvedBy = this[EventParticipantsTable.approvedBy]?.value,
        approvedAt = this[EventParticipantsTable.approvedAt],
    )
