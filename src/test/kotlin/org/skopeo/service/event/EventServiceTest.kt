// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.event

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.CreateFixtureCommand
import org.skopeo.model.EventParticipantStatus
import org.skopeo.model.MatchType
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.TeamType
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.model.ageInYears
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
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
    fun `participant roster carries sex, age, and the current rating band`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val dob = LocalDate.parse("1990-09-09")
        val player =
            users.provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = "p1",
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = "p1", isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = "Maria")),
                        sex = "Female",
                        dateOfBirth = dob,
                    ),
            )
        RatingRepository().setRating(
            userId = player.id,
            rating = BigDecimal("4.000000"),
            level = "4.0",
            confidence = BigDecimal("0.50"),
        )

        val view =
            service.create(token = token(uid = "host"), input = input(participants = listOf(element = player.id)))
                .shouldBeRight()
        val participant = view.participants.single()
        participant.sex shouldBe "Female"
        participant.age shouldBe ageInYears(dateOfBirth = dob, asOf = LocalDate.now())
        participant.rating?.currentRating?.toPlainString() shouldBe "4.000000"
        participant.rating?.currentLevel shouldBe "4.0"
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
    fun `publicByCode returns the event with participants and its matches, or NotFound (#138)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val created = service.create(token = token(uid = "host"), input = input(participants = listOf(p1.id, p2.id))).shouldBeRight()
        MatchRepository().createFixture(
            command =
                CreateFixtureCommand(
                    matchFormat = TeamType.SINGLES,
                    matchType = MatchType.OPEN_PLAY,
                    matchDate = LocalDate.parse("2026-03-02"),
                    team1UserIds = listOf(element = p1.id),
                    team2UserIds = listOf(element = p2.id),
                    team1Name = "p1",
                    team2Name = "p2",
                    createdBy = host.id,
                    eventId = created.event.id,
                ),
        )

        val public = service.publicByCode(token = token(uid = "host"), code = created.event.publicCode).shouldBeRight()
        public.name shouldBe "Spring Open"
        public.participants.map { it.userId }.toSet() shouldBe setOf(p1.id.toString(), p2.id.toString())
        public.matches shouldHaveSize 1
        public.matches.single().team1.single().publicCode shouldBe p1.publicCode

        service
            .publicByCode(token = token(uid = "host"), code = "ZZZZZZ")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `a caller whose token maps to no account is forbidden`() {
        // The "ghost" token resolves to no user — the staff gate denies before any work.
        service.create(token = token(uid = "ghost"), input = input()).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.list(token = token(uid = "ghost")).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `a player self-signs-up as PENDING and isn't on the approved roster yet (#201)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val player = provision(uid = "player")
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().event

        val public = service.selfSignup(token = token(uid = "player"), code = event.publicCode).shouldBeRight()
        public.viewerStatus shouldBe "PENDING"
        // The public roster lists APPROVED members only — the pending request isn't shown there.
        public.participants.none { it.userId == player.id.toString() }.shouldBeTrue()
        // Signing up again is idempotent (still a single PENDING request).
        service.selfSignup(token = token(uid = "player"), code = event.publicCode).shouldBeRight().viewerStatus shouldBe "PENDING"

        // The organizer sees the request; the approved roster (fixtures/seeding) excludes it.
        val view = service.get(token = token(uid = "host"), id = event.id).shouldBeRight()
        view.participants.single { it.userId == player.id }.status shouldBe EventParticipantStatus.PENDING
        view.event.participantIds.contains(element = player.id).shouldBeFalse()
    }

    @Test
    fun `a host approves a request, adding the player to the roster (#201)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val player = provision(uid = "player")
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().event
        service.selfSignup(token = token(uid = "player"), code = event.publicCode).shouldBeRight()

        val view =
            service
                .decideParticipant(
                    token = token(uid = "host"),
                    eventId = event.id,
                    userId = player.id,
                    status = EventParticipantStatus.APPROVED,
                ).shouldBeRight()
        view.participants.single { it.userId == player.id }.status shouldBe EventParticipantStatus.APPROVED
        view.event.participantIds.contains(element = player.id).shouldBeTrue()

        val public = service.publicByCode(token = token(uid = "player"), code = event.publicCode).shouldBeRight()
        public.viewerStatus shouldBe "APPROVED"
        public.participants.any { it.userId == player.id.toString() }.shouldBeTrue()
    }

    @Test
    fun `a host can hold a request and later approve it (#201)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val player = provision(uid = "player")
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().event
        service.selfSignup(token = token(uid = "player"), code = event.publicCode).shouldBeRight()

        service
            .decideParticipant(token = token(uid = "host"), eventId = event.id, userId = player.id, status = EventParticipantStatus.HOLD)
            .shouldBeRight()
            .participants
            .single { it.userId == player.id }
            .status shouldBe EventParticipantStatus.HOLD
        // On hold → still off the approved roster.
        service.get(
            token = token(uid = "host"),
            id = event.id,
        ).shouldBeRight().event.participantIds.contains(element = player.id).shouldBeFalse()

        // A held request can later be approved.
        service
            .decideParticipant(
                token = token(uid = "host"),
                eventId = event.id,
                userId = player.id,
                status = EventParticipantStatus.APPROVED,
            ).shouldBeRight()
            .event.participantIds
            .contains(element = player.id)
            .shouldBeTrue()
    }

    @Test
    fun `a non-staff caller cannot decide a request, and self-signup needs a profile (#201)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val player = provision(uid = "player")
        provision(uid = "outsider")
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().event
        service.selfSignup(token = token(uid = "player"), code = event.publicCode).shouldBeRight()

        service
            .decideParticipant(
                token = token(uid = "outsider"),
                eventId = event.id,
                userId = player.id,
                status = EventParticipantStatus.APPROVED,
            ).shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()

        // An unprovisioned token can't self-sign-up.
        service.selfSignup(
            token = token(uid = "ghost"),
            code = event.publicCode,
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `a host-added participant is approved outright (#201)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().event

        val view = service.addParticipant(token = token(uid = "host"), eventId = event.id, userId = p1.id).shouldBeRight()
        view.participants.single { it.userId == p1.id }.status shouldBe EventParticipantStatus.APPROVED
        view.event.participantIds.contains(element = p1.id).shouldBeTrue()
    }
}
