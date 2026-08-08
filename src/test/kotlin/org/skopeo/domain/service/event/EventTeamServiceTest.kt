// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.event

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.spyk
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.event.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateEventCommand
import org.skopeo.domain.model.Event
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.EventRepository
import org.skopeo.repository.EventTeamRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import java.time.LocalDate
import java.util.UUID

class EventTeamServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val events = EventRepository()
    private val teams = EventTeamRepository()
    private val service = EventTeamService(events = events, teams = teams, users = users)

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
        ).toDomain()

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private fun event(
        host: UUID,
        participants: List<UUID>,
        format: TeamType,
    ): Event =
        events
            .create(
                command =
                    CreateEventCommand(
                        name = "Cup",
                        startDate = LocalDate.now(),
                        endDate = LocalDate.now().plusDays(7),
                        participantIds = participants,
                        createdBy = host,
                        format = format,
                    ),
            ).toDomain()

    @Test
    fun `a host creates a singles team auto-named from the member`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "Alice")
        val ev = event(host = host.id, participants = listOf(element = p1.id), format = TeamType.SINGLES)

        val team =
            service.create(
                token = token(uid = "host"),
                eventId = ev.id,
                memberUserIds = listOf(element = p1.id),
                name = null,
            ).shouldBeRight()
        team.name shouldBe "Alice"
        team.members shouldHaveSize 1
        team.members.single().position shouldBe 1
        team.members.single().userId shouldBe p1.id.toString()
    }

    @Test
    fun `a doubles team keeps slot order and honors a name override`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "Alice")
        val p2 = provision(uid = "Bob")
        val ev = event(host = host.id, participants = listOf(p1.id, p2.id), format = TeamType.DOUBLES)

        val team =
            service
                .create(token = token(uid = "host"), eventId = ev.id, memberUserIds = listOf(p1.id, p2.id), name = "Dream Team")
                .shouldBeRight()
        team.name shouldBe "Dream Team"
        team.members.map { it.position } shouldBe listOf(1, 2)
        team.members.map { it.userId } shouldBe listOf(p1.id.toString(), p2.id.toString())
    }

    @Test
    fun `a team member must be an approved participant of the event`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "Alice")
        val outsider = provision(uid = "Zed")
        val ev = event(host = host.id, participants = listOf(element = p1.id), format = TeamType.SINGLES)

        val error =
            service.create(
                token = token(uid = "host"),
                eventId = ev.id,
                memberUserIds = listOf(element = outsider.id),
                name = null,
            ).shouldBeLeft()
        error.shouldBeInstanceOf<ServiceError.Validation>().message shouldContain "approved participants"
    }

    @Test
    fun `a two-member team is allowed on a singles event, independent of format`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "Alice")
        val p2 = provision(uid = "Bob")
        val ev = event(host = host.id, participants = listOf(p1.id, p2.id), format = TeamType.SINGLES)

        val team =
            service
                .create(token = token(uid = "host"), eventId = ev.id, memberUserIds = listOf(p1.id, p2.id), name = null)
                .shouldBeRight()
        team.members shouldHaveSize 2
        team.members.map { it.userId } shouldBe listOf(p1.id.toString(), p2.id.toString())
    }

    @Test
    fun `a one-member team is allowed on a doubles event, independent of format`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "Alice")
        val ev = event(host = host.id, participants = listOf(element = p1.id), format = TeamType.DOUBLES)

        val team =
            service
                .create(token = token(uid = "host"), eventId = ev.id, memberUserIds = listOf(element = p1.id), name = null)
                .shouldBeRight()
        team.members shouldHaveSize 1
        team.members.single().userId shouldBe p1.id.toString()
    }

    @Test
    fun `a team cannot have more than two members`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "Alice")
        val p2 = provision(uid = "Bob")
        val p3 = provision(uid = "Carol")
        val ev = event(host = host.id, participants = listOf(p1.id, p2.id, p3.id), format = TeamType.DOUBLES)

        val error =
            service
                .create(token = token(uid = "host"), eventId = ev.id, memberUserIds = listOf(p1.id, p2.id, p3.id), name = null)
                .shouldBeLeft()
        error.shouldBeInstanceOf<ServiceError.Validation>().message shouldContain "at most 2 members"
    }

    @Test
    fun `membership is exclusive within an event`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "Alice")
        val p2 = provision(uid = "Bob")
        val ev = event(host = host.id, participants = listOf(p1.id, p2.id), format = TeamType.SINGLES)
        service.create(token = token(uid = "host"), eventId = ev.id, memberUserIds = listOf(element = p1.id), name = null).shouldBeRight()

        val error =
            service.create(
                token = token(uid = "host"),
                eventId = ev.id,
                memberUserIds = listOf(element = p1.id),
                name = null,
            ).shouldBeLeft()
        error.shouldBeInstanceOf<ServiceError.Validation>().message shouldContain "already in another team"
    }

    @Test
    fun `update replaces members and a blank name re-auto-names`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "Alice")
        val p2 = provision(uid = "Bob")
        val ev = event(host = host.id, participants = listOf(p1.id, p2.id), format = TeamType.SINGLES)
        val team =
            service.create(
                token = token(uid = "host"),
                eventId = ev.id,
                memberUserIds = listOf(element = p1.id),
                name = "Custom",
            ).shouldBeRight()

        val updated =
            service
                .update(
                    token = token(uid = "host"),
                    eventId = ev.id,
                    teamId = UUID.fromString(team.id),
                    memberUserIds = listOf(element = p2.id),
                    name = "",
                ).shouldBeRight()
        updated.name shouldBe "Bob"
        updated.members.single().userId shouldBe p2.id.toString()
    }

    @Test
    fun `dissolve removes the team`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "Alice")
        val ev = event(host = host.id, participants = listOf(element = p1.id), format = TeamType.SINGLES)
        val team =
            service.create(
                token = token(uid = "host"),
                eventId = ev.id,
                memberUserIds = listOf(element = p1.id),
                name = null,
            ).shouldBeRight()

        service.dissolve(token = token(uid = "host"), eventId = ev.id, teamId = UUID.fromString(team.id)).shouldBeRight()
        service.list(token = token(uid = "host"), eventId = ev.id).shouldBeRight() shouldHaveSize 0
    }

    @Test
    fun `a non-staff caller is forbidden`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "Alice")
        val ev = event(host = host.id, participants = listOf(element = p1.id), format = TeamType.SINGLES)

        service
            .create(token = token(uid = "Alice"), eventId = ev.id, memberUserIds = listOf(element = p1.id), name = null)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `a host cannot manage another host's event teams`() {
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "other", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "Alice")
        val ev = event(host = owner.id, participants = listOf(element = p1.id), format = TeamType.SINGLES)

        service
            .create(token = token(uid = "other"), eventId = ev.id, memberUserIds = listOf(element = p1.id), name = null)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `a team needs at least one member`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val ev = event(host = host.id, participants = emptyList(), format = TeamType.SINGLES)

        service
            .create(token = token(uid = "host"), eventId = ev.id, memberUserIds = emptyList(), name = null)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
            .message shouldContain "at least one member"
    }

    @Test
    fun `a player cannot appear twice in one team`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "Alice")
        val ev = event(host = host.id, participants = listOf(element = p1.id), format = TeamType.DOUBLES)

        service
            .create(token = token(uid = "host"), eventId = ev.id, memberUserIds = listOf(p1.id, p1.id), name = null)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
            .message shouldContain "more than once"
    }

    @Test
    fun `updating a team that is not in the event is a not-found`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "Alice")
        val ev = event(host = host.id, participants = listOf(element = p1.id), format = TeamType.SINGLES)

        service
            .update(
                token = token(uid = "host"),
                eventId = ev.id,
                teamId = UUID.randomUUID(),
                memberUserIds = listOf(element = p1.id),
                name = null,
            ).shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `dissolving a team that is not in the event is a not-found`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val ev = event(host = host.id, participants = emptyList(), format = TeamType.SINGLES)

        service
            .dissolve(token = token(uid = "host"), eventId = ev.id, teamId = UUID.randomUUID())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `list returns an event's teams`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "Alice")
        val ev = event(host = host.id, participants = listOf(element = p1.id), format = TeamType.SINGLES)
        service.create(token = token(uid = "host"), eventId = ev.id, memberUserIds = listOf(element = p1.id), name = null).shouldBeRight()

        service.list(token = token(uid = "host"), eventId = ev.id).shouldBeRight() shouldHaveSize 1
    }

    @Test
    fun `update keeps the existing name when the name is null`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "Alice")
        val p2 = provision(uid = "Bob")
        val ev = event(host = host.id, participants = listOf(p1.id, p2.id), format = TeamType.SINGLES)
        val team =
            service
                .create(token = token(uid = "host"), eventId = ev.id, memberUserIds = listOf(element = p1.id), name = "Keepers")
                .shouldBeRight()

        val updated =
            service.update(
                token = token(uid = "host"),
                eventId = ev.id,
                teamId = UUID.fromString(team.id),
                memberUserIds = listOf(element = p2.id),
                name = null,
            ).shouldBeRight()
        updated.name shouldBe "Keepers"
        updated.members.single().userId shouldBe p2.id.toString()
    }

    @Test
    fun `update is a not-found when the team is deleted between the existence check and the write`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "Alice")
        val ev = event(host = host.id, participants = listOf(element = p1.id), format = TeamType.SINGLES)
        val created =
            service
                .create(token = token(uid = "host"), eventId = ev.id, memberUserIds = listOf(element = p1.id), name = "Keepers")
                .shouldBeRight()

        // The existence check (findById) still sees the team, but the write loses the race and returns null —
        // a concurrent dissolve a sequential test can't otherwise produce. A spy stubs only that one call.
        val teamsSpy = spyk(objToCopy = teams)
        every { teamsSpy.update(command = any()) } returns null
        val racyService = EventTeamService(events = events, teams = teamsSpy, users = users)

        racyService
            .update(
                token = token(uid = "host"),
                eventId = ev.id,
                teamId = UUID.fromString(created.id),
                memberUserIds = listOf(element = p1.id),
                name = "New",
            ).shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `the exclusive-membership query reports every assigned member when no team is excluded`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "Alice")
        val ev = event(host = host.id, participants = listOf(element = p1.id), format = TeamType.SINGLES)
        service.create(token = token(uid = "host"), eventId = ev.id, memberUserIds = listOf(element = p1.id), name = null).shouldBeRight()

        // Called with the default (no excludeTeamId) it returns the whole event's assigned members.
        teams.memberUserIdsInEvent(eventId = ev.id) shouldBe setOf(element = p1.id)
    }

    @Test
    fun `listing teams for an unknown event is a not-found`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        service
            .list(token = token(uid = "host"), eventId = UUID.randomUUID())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `creating a team on an unknown event is a not-found`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "Alice")
        // The caller is staff, so we pass the role gate and fail on the missing event itself.
        service
            .create(token = token(uid = "host"), eventId = UUID.randomUUID(), memberUserIds = listOf(element = p1.id), name = null)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `a plain host cannot modify teams on an ended event`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "Alice")
        val ended =
            events.create(
                command =
                    CreateEventCommand(
                        name = "Old Cup",
                        startDate = LocalDate.now().minusDays(30),
                        endDate = LocalDate.now().minusDays(20),
                        participantIds = listOf(element = p1.id),
                        createdBy = host.id,
                        format = TeamType.SINGLES,
                    ),
            ).toDomain()

        service
            .create(token = token(uid = "host"), eventId = ended.id, memberUserIds = listOf(element = p1.id), name = null)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }
}
