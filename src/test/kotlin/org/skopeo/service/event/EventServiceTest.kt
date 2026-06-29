// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.event

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
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.EventRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.time.LocalDate
import java.util.UUID

class EventServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val events = EventRepository()
    private val service = EventService(events = events, users = users)

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

    private fun input(
        name: String = "Spring Open",
        start: String = "2026-03-01",
        end: String = "2026-03-03",
        participants: List<UUID> = emptyList(),
    ) = CreateEventInput(
        name = name,
        startDate = LocalDate.parse(start),
        endDate = LocalDate.parse(end),
        participantIds = participants,
    )

    @Test
    fun `a host creates an event with a resolved participant roster`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")

        val view =
            service.create(token = token(uid = "host"), input = input(participants = listOf(p1.id, p2.id))).shouldBeRight()
        view.event.name shouldBe "Spring Open"
        view.event.publicCode.length shouldBe 6
        view.participants.map { it.userId } shouldBe listOf(p1.id, p2.id)
        view.participants.first().displayName shouldBe "p1"
    }

    @Test
    fun `create rejects a blank name, an inverted date range, and an unknown participant`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        service.create(token = token(uid = "host"), input = input(name = "  "))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.create(token = token(uid = "host"), input = input(start = "2026-03-05", end = "2026-03-01"))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.create(token = token(uid = "host"), input = input(participants = listOf(element = UUID.randomUUID())))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `list is scoped - a host sees only their own events, an admin sees all`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provision(uid = "host1", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "host2", roles = setOf(Capability.PLAYER, Capability.HOST))
        val a = service.create(token = token(uid = "host1"), input = input(name = "H1 Cup")).shouldBeRight()
        val b = service.create(token = token(uid = "host2"), input = input(name = "H2 Cup")).shouldBeRight()

        service.list(token = token(uid = "host1")).shouldBeRight().map { it.event.id } shouldBe listOf(element = a.event.id)
        service.list(token = token(uid = "admin")).shouldBeRight().map { it.event.id }.toSet() shouldBe setOf(a.event.id, b.event.id)
    }

    @Test
    fun `get returns the event or a not-found`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight()

        service.get(token = token(uid = "host"), id = event.event.id).shouldBeRight().event.id shouldBe event.event.id
        service.get(token = token(uid = "host"), id = UUID.randomUUID()).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `add and remove participants, idempotently`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight()

        service.addParticipant(token = token(uid = "host"), eventId = event.event.id, userId = p1.id).shouldBeRight()
        // Re-adding the same participant is a no-op (no duplicate).
        val after = service.addParticipant(token = token(uid = "host"), eventId = event.event.id, userId = p1.id).shouldBeRight()
        after.participants shouldHaveSize 1

        val removed = service.removeParticipant(token = token(uid = "host"), eventId = event.event.id, userId = p1.id).shouldBeRight()
        removed.participants shouldHaveSize 0
    }

    @Test
    fun `adding to a missing event is a not-found, adding an unknown user is a validation error`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight()

        service.addParticipant(token = token(uid = "host"), eventId = UUID.randomUUID(), userId = provision(uid = "p9").id)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
        service.addParticipant(token = token(uid = "host"), eventId = event.event.id, userId = UUID.randomUUID())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        // Removing from a missing event is also a not-found.
        service.removeParticipant(token = token(uid = "host"), eventId = UUID.randomUUID(), userId = provision(uid = "p8").id)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `a non-staff player cannot create, list, get, or manage participants`() {
        provision(uid = "player", roles = setOf(element = Capability.PLAYER))
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight()

        service.create(token = token(uid = "player"), input = input()).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.list(token = token(uid = "player")).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.get(token = token(uid = "player"), id = event.event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.addParticipant(token = token(uid = "player"), eventId = event.event.id, userId = event.event.id)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.removeParticipant(token = token(uid = "player"), eventId = event.event.id, userId = event.event.id)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `a caller whose token maps to no account is forbidden`() {
        // The "ghost" token resolves to no user — the staff gate denies before any work.
        service.create(token = token(uid = "ghost"), input = input()).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.list(token = token(uid = "ghost")).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }
}
