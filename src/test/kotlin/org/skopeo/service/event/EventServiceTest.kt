// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.event

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.dto.event.toResponse
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuthProvider
import org.skopeo.model.AwardStatus
import org.skopeo.model.Capability
import org.skopeo.model.CreateClubCommand
import org.skopeo.model.CreateFixtureCommand
import org.skopeo.model.CreatePlaceholderCommand
import org.skopeo.model.EventParticipantStatus
import org.skopeo.model.EventType
import org.skopeo.model.Match
import org.skopeo.model.MatchSetResult
import org.skopeo.model.MatchType
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.TeamType
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.model.ageInYears
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.ClubRepository
import org.skopeo.repository.EventRepository
import org.skopeo.repository.EventsTable
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
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
    private val clubs = ClubRepository()
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

    // A login-less placeholder ("dummy") player (#496/#505) — a real users row with placeholder = true.
    private fun placeholder(displayName: String): User =
        users.createPlaceholder(command = CreatePlaceholderCommand(displayName = displayName, sex = "Male"))

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    // Default to a currently-running event (ends a week out) so host data-entry stays allowed; the
    // expired-event tests (#310) pass explicit past dates.
    private fun input(
        name: String = "Spring Open",
        start: String = LocalDate.now().toString(),
        end: String = LocalDate.now().plusDays(7).toString(),
        participants: List<UUID> = emptyList(),
        clubId: UUID? = null,
        type: EventType = EventType.OPEN_PLAY,
        minPoints: Int? = null,
        maxPoints: Int? = null,
        validityStart: LocalDate? = null,
        validityEnd: LocalDate? = null,
    ) = CreateEventInput(
        name = name,
        startDate = LocalDate.parse(start),
        endDate = LocalDate.parse(end),
        participantIds = participants,
        clubId = clubId,
        type = type,
        minPointsPerMatch = minPoints,
        maxPointsPerMatch = maxPoints,
        pointValidityStart = validityStart,
        pointValidityEnd = validityEnd,
    )

    private fun config(
        min: Int,
        max: Int,
        start: LocalDate = LocalDate.now(),
        end: LocalDate = LocalDate.now().plusDays(30),
    ) = PointsConfigInput(minPoints = min, maxPoints = max, validityStart = start, validityEnd = end)

    // A create input for an event under a club: a club event of any type now requires a points config
    // (OPEN_PLAY unified with LEAGUE/TOURNAMENT), so a 2..8 window fits every global policy (min ≥ 1).
    private fun clubInput(
        clubId: UUID,
        type: EventType = EventType.OPEN_PLAY,
    ) = input(
        clubId = clubId,
        type = type,
        minPoints = 2,
        maxPoints = 8,
        validityStart = LocalDate.now(),
        validityEnd = LocalDate.now().plusDays(10),
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
        view.participants.map { it.userId }.shouldContainExactlyInAnyOrder(p1.id, p2.id)
        // Participant order isn't guaranteed, so look p1 up by id rather than assuming it's first.
        view.participants.single { it.userId == p1.id }.displayName shouldBe "p1"
        view.club.shouldBeNull() // clubless by default
    }

    @Test
    fun `event participants carry the placeholder flag, true for a dummy and false for a real player (#505)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val real = provision(uid = "real")
        val dummy = placeholder(displayName = "Dummy")

        val view =
            service.create(token = token(uid = "host"), input = input(participants = listOf(real.id, dummy.id))).shouldBeRight()

        view.participants.single { it.userId == dummy.id }.placeholder.shouldBeTrue()
        view.participants.single { it.userId == real.id }.placeholder.shouldBeFalse()
        // The DTO mapper carries the flag through verbatim (#505).
        view.toResponse().participants.single { it.userId == dummy.id.toString() }.isPlaceholder.shouldBeTrue()
        view.toResponse().participants.single { it.userId == real.id.toString() }.isPlaceholder.shouldBeFalse()
    }

    @Test
    fun `creating an event writes an Activity Log entry (#334)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val view = service.create(token = token(uid = "host"), input = input()).shouldBeRight()

        AuditRepository().list(actions = listOf(element = AuditAction.EVENT_CREATED), limit = 10, offset = 0).first.single().let {
            it.actorUserId shouldBe host.id
            it.entityId shouldBe view.event.id
            it.summary shouldBe "Created event Spring Open"
            it.details["publicCode"] shouldBe view.event.publicCode
        }
    }

    @Test
    fun `an event can be created under a club, and an unknown club is rejected (#313)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val club = clubs.create(command = CreateClubCommand(name = "Downtown TC", createdBy = host.id))

        // A club event of any type now requires a points config (OPEN_PLAY unified); supply a valid window.
        val view = service.create(token = token(uid = "host"), input = clubInput(clubId = club.id)).shouldBeRight()
        view.club?.id shouldBe club.id
        view.club?.name shouldBe "Downtown TC"
        view.event.clubId shouldBe club.id

        // An unknown club is rejected at create.
        service.create(token = token(uid = "host"), input = clubInput(clubId = UUID.randomUUID()))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `setClub sets, changes, and clears an event's club (#319)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val clubA = clubs.create(command = CreateClubCommand(name = "Downtown TC", createdBy = host.id))
        val clubB = clubs.create(command = CreateClubCommand(name = "West End", createdBy = host.id))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight()
        event.club.shouldBeNull() // clubless to start

        // Add a club.
        service.setClub(token = token(uid = "host"), id = event.event.id, clubId = clubA.id).shouldBeRight().club?.id shouldBe clubA.id
        // Change it.
        service.setClub(token = token(uid = "host"), id = event.event.id, clubId = clubB.id).shouldBeRight().let {
            it.club?.id shouldBe clubB.id
            it.club?.name shouldBe "West End"
        }
        // Clear it (back to Open).
        service.setClub(token = token(uid = "host"), id = event.event.id, clubId = null).shouldBeRight().club.shouldBeNull()

        // Each club change writes an Activity Log entry (#354); the newest records the clear as "Open".
        val entries = AuditRepository().list(actions = listOf(element = AuditAction.EVENT_CLUB_CHANGED), limit = 10, offset = 0).first
        entries shouldHaveSize 3
        entries.first().actorUserId shouldBe host.id
        entries.first().summary shouldBe "Set event ${event.event.name} club to Open"
        entries.first().details["newClubId"].shouldBeNull()
    }

    @Test
    fun `setClub validates the club and is owner-or-admin only (#319)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "other", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val club = clubs.create(command = CreateClubCommand(name = "Downtown TC", createdBy = host.id))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight()

        // Unknown club → Validation.
        service.setClub(token = token(uid = "host"), id = event.event.id, clubId = UUID.randomUUID())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
        // A different host (not the creator) → Forbidden.
        service.setClub(token = token(uid = "other"), id = event.event.id, clubId = club.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        // An ADMINISTRATOR may edit any event's club.
        service.setClub(token = token(uid = "admin"), id = event.event.id, clubId = club.id).shouldBeRight().club?.id shouldBe club.id
        // Unknown event → NotFound.
        service.setClub(token = token(uid = "admin"), id = UUID.randomUUID(), clubId = null)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `setCalcPriority persists and is administrator-only (#335)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight()

        // A non-admin staff member (host) cannot set the calculation priority.
        service.setCalcPriority(token = token(uid = "host"), id = event.event.id, priority = 5.0)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        // An ADMINISTRATOR sets it, and it round-trips on the view.
        service.setCalcPriority(token = token(uid = "admin"), id = event.event.id, priority = 5.0)
            .shouldBeRight().event.calcPriority shouldBe 5.0
        // Unknown event → NotFound.
        service.setCalcPriority(token = token(uid = "admin"), id = UUID.randomUUID(), priority = 1.0)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `the organizer view surfaces the filing host as the creator (#270)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val created = service.create(token = token(uid = "host"), input = input()).shouldBeRight()

        created.creator?.displayName shouldBe "host"
        created.creator?.publicCode shouldBe host.publicCode
        // Also present on the list and single-event views (both go through toView).
        service.list(token = token(uid = "host")).shouldBeRight().single().creator?.publicCode shouldBe host.publicCode
        service.get(token = token(uid = "host"), id = created.event.id).shouldBeRight().creator?.publicCode shouldBe host.publicCode
    }

    @Test
    fun `an event whose creator was removed has a null creator (#270)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().event

        // Orphan the creator — the FK is ON DELETE SET NULL (created_by becomes null).
        transaction { EventsTable.update(where = { EventsTable.id eq event.id }) { it[createdBy] = null } }

        service.get(token = token(uid = "host"), id = event.id).shouldBeRight().creator.shouldBeNull()
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
    fun `a host cannot add a participant to an expired event, but an admin or club owner can (#310)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val expired =
            input(start = LocalDate.now().minusDays(3).toString(), end = LocalDate.now().minusDays(1).toString())
        val event = service.create(token = token(uid = "host"), input = expired).shouldBeRight()

        // The event has ended → the HOST is blocked.
        service.addParticipant(token = token(uid = "host"), eventId = event.event.id, userId = p1.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()

        // An ADMINISTRATOR may still add.
        service.addParticipant(token = token(uid = "admin"), eventId = event.event.id, userId = p1.id)
            .shouldBeRight()
            .participants shouldHaveSize 1

        // A CLUB_OWNER may still add too (#310 follow-up).
        service.addParticipant(token = token(uid = "owner"), eventId = event.event.id, userId = p2.id)
            .shouldBeRight()
            .participants shouldHaveSize 2
    }

    @Test
    fun `a host may still add on the event's last day (#310)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val endsToday = input(start = LocalDate.now().minusDays(2).toString(), end = LocalDate.now().toString())
        val event = service.create(token = token(uid = "host"), input = endsToday).shouldBeRight()

        // today == endDate is not yet expired → still allowed.
        service.addParticipant(token = token(uid = "host"), eventId = event.event.id, userId = p1.id).shouldBeRight()
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
    fun `publicByCode surfaces the organizing club's name, and null when clubless (#313)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val club = clubs.create(command = CreateClubCommand(name = "Downtown TC", createdBy = host.id))
        val underClub = service.create(token = token(uid = "host"), input = clubInput(clubId = club.id)).shouldBeRight()
        val clubless = service.create(token = token(uid = "host"), input = input()).shouldBeRight()

        service.publicByCode(token = null, code = underClub.event.publicCode).shouldBeRight().clubName shouldBe "Downtown TC"
        service.publicByCode(token = null, code = clubless.event.publicCode).shouldBeRight().clubName.shouldBeNull()
    }

    // --- Event deletion (#243) ---

    private val matchRepo = MatchRepository()

    private fun seedFixture(
        eventId: UUID,
        host: User,
        p1: User,
        p2: User,
    ): Match =
        matchRepo.createFixture(
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
                    eventId = eventId,
                ),
        )

    private fun recordResult(match: Match) {
        matchRepo.addResult(
            matchId = match.id,
            sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 4, winnerTeamId = match.team1.teamId)),
            winnerTeamId = match.team1.teamId,
            recordedBy = match.createdBy!!,
            completedAt = LocalDateTime.now(),
        )
    }

    @Test
    fun `setActive reports false when no event row matches`() {
        events.setActive(id = UUID.randomUUID(), active = false, disabledAt = null).shouldBeFalse()
    }

    @Test
    fun `a host deletes an event with no matches, and it drops off their list`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().event

        service.delete(token = token(uid = "host"), id = event.id).shouldBeRight()

        events.findById(id = event.id)!!.isActive.shouldBeFalse()
        service.list(token = token(uid = "host")).shouldBeRight() shouldHaveSize 0
        // The soft-delete writes an Activity Log entry (#354).
        AuditRepository().list(actions = listOf(element = AuditAction.EVENT_DELETED), limit = 10, offset = 0).first.single().let {
            it.actorUserId shouldBe host.id
            it.entityId shouldBe event.id
            it.summary shouldBe "Deleted event ${event.name}"
        }
    }

    @Test
    fun `deleting an event soft-disables its remaining scheduled fixtures`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val event = service.create(token = token(uid = "host"), input = input(participants = listOf(p1.id, p2.id))).shouldBeRight().event
        seedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2)

        service.delete(token = token(uid = "host"), id = event.id).shouldBeRight()

        matchRepo.listByEvent(eventId = event.id) shouldHaveSize 0
    }

    @Test
    fun `an event with a recorded but unrated match is refused, advising match deletion first`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val event = service.create(token = token(uid = "host"), input = input(participants = listOf(p1.id, p2.id))).shouldBeRight().event
        recordResult(match = seedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2))

        val error = service.delete(token = token(uid = "host"), id = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Conflict>()
        error.message shouldContain "recorded matches first"
        events.findById(id = event.id)!!.isActive.shouldBeTrue()
    }

    @Test
    fun `an event with a rated match cannot be deleted`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val event = service.create(token = token(uid = "host"), input = input(participants = listOf(p1.id, p2.id))).shouldBeRight().event
        val match = seedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2)
        recordResult(match = match)
        matchRepo.markRated(matchId = match.id, ratedAt = LocalDateTime.now(), ratedBy = host.id)

        val error = service.delete(token = token(uid = "host"), id = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Conflict>()
        error.message shouldContain "rated matches"
        events.findById(id = event.id)!!.isActive.shouldBeTrue()
    }

    @Test
    fun `a host cannot delete another host's event, but an administrator can`() {
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "other", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val event = service.create(token = token(uid = "owner"), input = input()).shouldBeRight().event

        service.delete(token = token(uid = "other"), id = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        events.findById(id = event.id)!!.isActive.shouldBeTrue()

        service.delete(token = token(uid = "admin"), id = event.id).shouldBeRight()
        events.findById(id = event.id)!!.isActive.shouldBeFalse()
        owner.id shouldBe event.createdBy
    }

    @Test
    fun `a host renames their own event, trimming the name, and it writes an Activity Log entry (#354)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input(name = "Spring Open")).shouldBeRight().event

        val renamed = service.rename(token = token(uid = "host"), id = event.id, name = "  Summer Classic  ").shouldBeRight()

        renamed.event.name shouldBe "Summer Classic"
        events.findById(id = event.id)!!.name shouldBe "Summer Classic"
        AuditRepository().list(actions = listOf(element = AuditAction.EVENT_RENAMED), limit = 10, offset = 0).first.single().let {
            it.actorUserId shouldBe host.id
            it.entityId shouldBe event.id
            it.summary shouldBe "Renamed event Spring Open → Summer Classic"
            it.details["oldName"] shouldBe "Spring Open"
        }
    }

    @Test
    fun `rename rejects a blank name`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().event

        service.rename(token = token(uid = "host"), id = event.id, name = "   ")
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        events.findById(id = event.id)!!.name shouldBe "Spring Open"
    }

    @Test
    fun `a host cannot rename another host's event, but an administrator can`() {
        provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "other", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val event = service.create(token = token(uid = "owner"), input = input(name = "Owner Cup")).shouldBeRight().event

        service.rename(token = token(uid = "other"), id = event.id, name = "Hijacked")
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        events.findById(id = event.id)!!.name shouldBe "Owner Cup"

        service.rename(token = token(uid = "admin"), id = event.id, name = "Admin Renamed").shouldBeRight()
        events.findById(id = event.id)!!.name shouldBe "Admin Renamed"
    }

    @Test
    fun `an event is created with each type, defaulting to OPEN_PLAY (#403)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        service.create(token = token(uid = "host"), input = input(name = "Default")).shouldBeRight()
            .event.type shouldBe EventType.OPEN_PLAY
        // A budgeted-type event (#403 Phase C) requires a points config; supply one within the global policy.
        service.create(
            token = token(uid = "host"),
            input =
                input(
                    name = "League",
                    type = EventType.LEAGUE,
                    minPoints = 5,
                    maxPoints = 40,
                    validityStart = LocalDate.now(),
                    validityEnd = LocalDate.now().plusDays(30),
                ),
        ).shouldBeRight().event.type shouldBe EventType.LEAGUE
        val tourney =
            service.create(
                token = token(uid = "host"),
                input =
                    input(
                        name = "Tourney",
                        type = EventType.TOURNAMENT,
                        minPoints = 10,
                        maxPoints = 100,
                        validityStart = LocalDate.now(),
                        validityEnd = LocalDate.now().plusDays(60),
                    ),
            ).shouldBeRight()
        tourney.event.type shouldBe EventType.TOURNAMENT
        // The type round-trips through persistence.
        events.findById(id = tourney.event.id)!!.type shouldBe EventType.TOURNAMENT
    }

    @Test
    fun `a host finalizes their own event, setting state and writing an Activity Log entry (#403)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().event
        event.isFinalized.shouldBeFalse()

        val finalized = service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        finalized.event.isFinalized.shouldBeTrue()
        finalized.event.finalizedBy shouldBe host.id
        val persisted = events.findById(id = event.id)!!
        persisted.isFinalized.shouldBeTrue()
        persisted.finalizedBy shouldBe host.id
        AuditRepository().list(actions = listOf(element = AuditAction.EVENT_FINALIZED), limit = 10, offset = 0).first.single().let {
            it.actorUserId shouldBe host.id
            it.entityId shouldBe event.id
            it.summary shouldBe "Finalized event Spring Open"
        }
    }

    @Test
    fun `finalize is terminal - a second finalize is rejected as Validation (#403)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().event
        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        service.finalize(token = token(uid = "host"), id = event.id)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `a non-staff caller cannot finalize, and a host cannot finalize another host's event (#403)`() {
        provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "other", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "owner"), input = input()).shouldBeRight().event

        service.finalize(token = token(uid = "ghost"), id = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.finalize(token = token(uid = "other"), id = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        events.findById(id = event.id)!!.isFinalized.shouldBeFalse()

        // A club owner may finalize an event they didn't create.
        provision(uid = "clubowner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        service.finalize(token = token(uid = "clubowner"), id = event.id).shouldBeRight().event.isFinalized.shouldBeTrue()
    }

    @Test
    fun `an administrator may finalize any event (#403)`() {
        provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val event = service.create(token = token(uid = "owner"), input = input()).shouldBeRight().event

        service.finalize(token = token(uid = "admin"), id = event.id).shouldBeRight().event.isFinalized.shouldBeTrue()
    }

    @Test
    fun `finalizing a non-existent event is NotFound, and a deleted event is Validation (#403)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        service.finalize(token = token(uid = "host"), id = UUID.randomUUID())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()

        // A soft-deleted (inactive) event cannot be finalized.
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().event
        service.delete(token = token(uid = "host"), id = event.id).shouldBeRight()
        service.finalize(token = token(uid = "host"), id = event.id)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `a finalized event rejects rename, set-club, and participant mutations as Validation (#403)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val player = provision(uid = "player")
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().event
        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        service.rename(token = token(uid = "host"), id = event.id, name = "New Name")
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.setClub(token = token(uid = "host"), id = event.id, clubId = null)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.addParticipant(token = token(uid = "host"), eventId = event.id, userId = player.id)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.decideParticipant(
            token = token(uid = "host"),
            eventId = event.id,
            userId = player.id,
            status = EventParticipantStatus.APPROVED,
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    // --- Un-finalize (#477). ---

    @Test
    fun `a host un-finalizes their own event, clearing the flag and writing an Activity Log entry (#477)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().event
        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        val reopened = service.unfinalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        reopened.event.isFinalized.shouldBeFalse()
        reopened.event.finalizedBy.shouldBeNull()
        val persisted = events.findById(id = event.id)!!
        persisted.isFinalized.shouldBeFalse()
        persisted.finalizedBy.shouldBeNull()
        persisted.finalizedAt.shouldBeNull()
        AuditRepository().list(actions = listOf(element = AuditAction.EVENT_UNFINALIZED), limit = 10, offset = 0).first.single().let {
            it.actorUserId shouldBe host.id
            it.entityId shouldBe event.id
            it.entityType shouldBe AuditEntityType.EVENT
            it.summary shouldContain "Un-finalized event Spring Open"
        }
    }

    @Test
    fun `an administrator may un-finalize any event, and a club owner too (#477)`() {
        provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val event = service.create(token = token(uid = "owner"), input = input()).shouldBeRight().event
        service.finalize(token = token(uid = "owner"), id = event.id).shouldBeRight()

        service.unfinalize(token = token(uid = "admin"), id = event.id).shouldBeRight().event.isFinalized.shouldBeFalse()

        // A club owner may also un-finalize an event they didn't create.
        provision(uid = "clubowner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        service.finalize(token = token(uid = "owner"), id = event.id).shouldBeRight()
        service.unfinalize(token = token(uid = "clubowner"), id = event.id).shouldBeRight().event.isFinalized.shouldBeFalse()
    }

    @Test
    fun `a non-staff caller cannot un-finalize, and a host cannot un-finalize another host's event (#477)`() {
        provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "other", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "owner"), input = input()).shouldBeRight().event
        service.finalize(token = token(uid = "owner"), id = event.id).shouldBeRight()

        service.unfinalize(token = token(uid = "ghost"), id = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.unfinalize(token = token(uid = "other"), id = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        events.findById(id = event.id)!!.isFinalized.shouldBeTrue()
    }

    @Test
    fun `un-finalizing a non-finalized event is Validation, and a non-existent event is NotFound (#477)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().event

        service.unfinalize(token = token(uid = "host"), id = event.id)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
            .message shouldContain "not finalized"
        service.unfinalize(token = token(uid = "host"), id = UUID.randomUUID())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `un-finalize is refused when any of the event's matches are already rated (#477)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        val match = seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2, designated = 30)
        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()
        // The Administrator has since run the rating-calculation trigger on this match.
        matchRepo.markRated(matchId = match.id, ratedAt = LocalDateTime.now(), ratedBy = host.id)

        val error =
            service.unfinalize(token = token(uid = "host"), id = event.id)
                .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        error.message shouldContain "already-rated"
        // The event stays finalized and its award stays live — nothing was reversed.
        events.findById(id = event.id)!!.isFinalized.shouldBeTrue()
        awardRepo.listActiveByEvent(eventId = event.id) shouldHaveSize 1
    }

    @Test
    fun `un-finalize revokes every active award for the event, appending a revocation marker each (#477)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2, designated = 30)
        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()
        awardRepo.listActiveByEvent(eventId = event.id) shouldHaveSize 1

        service.unfinalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // No active awards remain for the event; the original flips to REVOKED and a marker row is appended.
        awardRepo.listActiveByEvent(eventId = event.id) shouldHaveSize 0
        val rows = awardRepo.listByUser(userId = p1.id)
        rows shouldHaveSize 2
        rows.count { it.status == AwardStatus.ACTIVE } shouldBe 0
        val marker = rows.single { it.revokesAwardId != null }
        marker.status shouldBe AwardStatus.REVOKED
        marker.points shouldBe BigDecimal("0.0000")
        marker.reason.shouldNotBeNull() shouldContain "Reversed on un-finalize"
        // The reversal is audited with the revoked count.
        AuditRepository().list(actions = listOf(element = AuditAction.EVENT_UNFINALIZED), limit = 10, offset = 0)
            .first.single().details["awardsRevoked"] shouldBe "1"
    }

    @Test
    fun `un-finalize then re-finalize is allowed, letting a corrected event award again (#477)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2, designated = 30)
        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()
        service.unfinalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // A fresh ACTIVE award exists after re-finalize; the earlier one stays revoked.
        awardRepo.listActiveByEvent(eventId = event.id) shouldHaveSize 1
    }

    @Test
    fun `renaming a non-existent event is NotFound, and a non-staff caller is forbidden`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        service.rename(token = token(uid = "host"), id = UUID.randomUUID(), name = "X")
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
        service.rename(token = token(uid = "ghost"), id = UUID.randomUUID(), name = "X")
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `deleting a non-existent event is NotFound, and a non-staff caller is forbidden`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        service
            .delete(token = token(uid = "host"), id = UUID.randomUUID())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
        service.delete(token = token(uid = "ghost"), id = UUID.randomUUID()).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
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

    @Test
    fun `a host-add promotes an existing pending request to APPROVED (#201)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val player = provision(uid = "player")
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().event
        service.selfSignup(token = token(uid = "player"), code = event.publicCode).shouldBeRight()

        val view = service.addParticipant(token = token(uid = "host"), eventId = event.id, userId = player.id).shouldBeRight()
        view.participants.single { it.userId == player.id }.status shouldBe EventParticipantStatus.APPROVED
        view.event.participantIds.contains(element = player.id).shouldBeTrue()
    }

    @Test
    fun `signup and decide report not-found and reject a non-decision status (#201)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val player = provision(uid = "player")
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().event
        service.selfSignup(token = token(uid = "player"), code = event.publicCode).shouldBeRight()

        // Unknown code / event id.
        service.selfSignup(token = token(uid = "player"), code = "ZZZZZZ").shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
        service
            .decideParticipant(
                token = token(uid = "host"),
                eventId = UUID.randomUUID(),
                userId = player.id,
                status = EventParticipantStatus.APPROVED,
            ).shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
        // PENDING is not a valid decision (only APPROVED/HOLD).
        service
            .decideParticipant(
                token = token(uid = "host"),
                eventId = event.id,
                userId = player.id,
                status = EventParticipantStatus.PENDING,
            ).shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
        // The repository self-signup is a no-op (null) for an unknown event.
        events.selfSignup(eventId = UUID.randomUUID(), userId = player.id) shouldBe null
    }

    @Test
    fun `the public event summary has no viewer status for an unprovisioned viewer (#201)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().event

        val public = service.publicByCode(token = token(uid = "ghost"), code = event.publicCode).shouldBeRight()
        public.viewerStatus shouldBe null
    }

    @Test
    fun `the public event summary is viewable anonymously, with no viewer status (#193)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input()).shouldBeRight().event

        val public = service.publicByCode(token = null, code = event.publicCode).shouldBeRight()
        public.viewerStatus shouldBe null
    }

    @Test
    fun `myEvents lists the caller's events with their standing, empty for an unprovisioned caller (#202)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val player = provision(uid = "player")
        val a = service.create(token = token(uid = "host"), input = input(name = "Alpha")).shouldBeRight().event
        val b = service.create(token = token(uid = "host"), input = input(name = "Bravo")).shouldBeRight().event
        service.selfSignup(token = token(uid = "player"), code = a.publicCode).shouldBeRight() // PENDING on A
        service.addParticipant(token = token(uid = "host"), eventId = b.id, userId = player.id).shouldBeRight() // APPROVED on B

        val mine = service.myEvents(token = token(uid = "player")).shouldBeRight()
        mine.map { it.event.publicCode to it.status }.toSet() shouldBe
            setOf(
                a.publicCode to EventParticipantStatus.PENDING,
                b.publicCode to EventParticipantStatus.APPROVED,
            )

        // An unprovisioned caller simply has no events.
        service.myEvents(token = token(uid = "ghost")).shouldBeRight() shouldBe emptyList()
    }

    @Test
    fun `completedResultCounts is batched and counts only recorded results per event (#483)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val withResults =
            service.create(
                token = token(uid = "host"),
                input = input(name = "WithResults", participants = listOf(p1.id, p2.id)),
            ).shouldBeRight().event
        val scheduledOnly =
            service.create(
                token = token(uid = "host"),
                input = input(name = "ScheduledOnly", participants = listOf(p1.id, p2.id)),
            ).shouldBeRight().event
        recordResult(match = seedFixture(eventId = withResults.id, host = host, p1 = p1, p2 = p2))
        // A scheduled fixture (no recorded result) must not lift the count off zero.
        seedFixture(eventId = scheduledOnly.id, host = host, p1 = p1, p2 = p2)

        val counts = service.completedResultCounts(eventIds = listOf(withResults.id, scheduledOnly.id))
        counts[withResults.id] shouldBe 1
        counts[scheduledOnly.id].shouldBeNull()
    }

    @Test
    fun `MyEventResponse carries isFinalized and completedMatchCount for the client's buckets (#483)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val created =
            service.create(
                token = token(uid = "host"),
                input = input(name = "Finalized", participants = listOf(p1.id, p2.id)),
            ).shouldBeRight().event
        recordResult(match = seedFixture(eventId = created.id, host = host, p1 = p1, p2 = p2))
        service.finalize(token = token(uid = "host"), id = created.id).shouldBeRight()

        // p1 is an approved participant, so the event shows on their "Events history".
        val mine = service.myEvents(token = token(uid = "p1")).shouldBeRight().single { it.event.id == created.id }
        val counts = service.completedResultCounts(eventIds = listOf(element = created.id))
        val dto = mine.toResponse(completedMatchCount = counts[created.id] ?: 0)
        dto.isFinalized.shouldBeTrue()
        dto.completedMatchCount shouldBe 1
    }

    // --- Points config (#403 Phase C). Global policy seeded by V16: OPEN_PLAY 1..10/30, LEAGUE 5..50/90,
    // TOURNAMENT 10..500/365. ---

    @Test
    fun `an OPEN_PLAY event needs no points config and stores none (#403)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val view = service.create(token = token(uid = "host"), input = input(type = EventType.OPEN_PLAY)).shouldBeRight()
        view.event.minPointsPerMatch.shouldBeNull()
        view.event.pointValidityStart.shouldBeNull()
    }

    @Test
    fun `a budgeted event create validates the points config against the global policy (#403)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val start = LocalDate.now()
        val end = start.plusDays(30)

        // Happy path: within LEAGUE's global 5..50 window and 90-day validity.
        val ok =
            service.create(
                token = token(uid = "host"),
                input =
                    input(
                        type = EventType.LEAGUE,
                        minPoints = 5,
                        maxPoints = 40,
                        validityStart = start,
                        validityEnd = end,
                    ),
            ).shouldBeRight()
        ok.event.minPointsPerMatch shouldBe 5
        ok.event.maxPointsPerMatch shouldBe 40
        ok.event.pointValidityEnd shouldBe end
    }

    @Test
    fun `a budgeted event create rejects min below the global minimum (#403)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        service.create(
            token = token(uid = "host"),
            // minPoints 1 is below LEAGUE's global min of 5.
            input =
                input(
                    type = EventType.LEAGUE,
                    minPoints = 1,
                    maxPoints = 40,
                    validityStart = LocalDate.now(),
                    validityEnd = LocalDate.now().plusDays(10),
                ),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `a budgeted event create rejects a validity window over the global maximum (#403)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        service.create(
            token = token(uid = "host"),
            // A 200-day validity window exceeds LEAGUE's 90-day max.
            input =
                input(
                    type = EventType.LEAGUE,
                    minPoints = 5,
                    maxPoints = 40,
                    validityStart = LocalDate.now(),
                    validityEnd = LocalDate.now().plusDays(200),
                ),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `a club event opting in requires all four config fields together, but may award none (#466)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val club = clubs.create(command = CreateClubCommand(name = "Downtown TC", createdBy = host.id))
        val start = LocalDate.now()
        val end = start.plusDays(10)

        // Points are opt-in for every event/club (#466): a club event with NO config fields awards no
        // points and is valid (no config stored).
        val noPoints =
            service.create(token = token(uid = "host"), input = input(type = EventType.TOURNAMENT, clubId = club.id)).shouldBeRight()
        noPoints.event.minPointsPerMatch.shouldBeNull()
        noPoints.event.pointValidityStart.shouldBeNull()

        // But opting in with a PARTIAL config (some but not all four fields) is still rejected.
        service.create(
            token = token(uid = "host"),
            input = input(type = EventType.TOURNAMENT, clubId = club.id, maxPoints = 100, validityStart = start, validityEnd = end),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.create(
            token = token(uid = "host"),
            input = input(type = EventType.TOURNAMENT, clubId = club.id, minPoints = 10, validityStart = start, validityEnd = end),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.create(
            token = token(uid = "host"),
            input = input(type = EventType.TOURNAMENT, clubId = club.id, minPoints = 10, maxPoints = 100, validityEnd = end),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.create(
            token = token(uid = "host"),
            input = input(type = EventType.TOURNAMENT, clubId = club.id, minPoints = 10, maxPoints = 100, validityStart = start),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `a clubless budgeted event create may defer its points config, but a partial config is rejected (#429)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        // No club and no config fields → allowed; the config is deferred (settable later once a club is set).
        val deferred =
            service.create(token = token(uid = "host"), input = input(type = EventType.TOURNAMENT)).shouldBeRight()
        deferred.event.type shouldBe EventType.TOURNAMENT
        deferred.event.minPointsPerMatch.shouldBeNull()
        deferred.event.pointValidityStart.shouldBeNull()

        // No club but a partial config supplied → still rejected (a partial config is never silently dropped).
        service.create(
            token = token(uid = "host"),
            input = input(type = EventType.TOURNAMENT, minPoints = 10),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `an OPEN_PLAY event with a club validates its points config when opting in (unify, #466)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val club = clubs.create(command = CreateClubCommand(name = "Downtown TC", createdBy = host.id))
        val start = LocalDate.now()
        val end = start.plusDays(10)

        // Points are opt-in for OPEN_PLAY too (#466): a club OPEN_PLAY event with no config awards nothing.
        service.create(token = token(uid = "host"), input = input(type = EventType.OPEN_PLAY, clubId = club.id))
            .shouldBeRight().event.minPointsPerMatch.shouldBeNull()

        // A window beyond the global OPEN_PLAY max (10) is rejected.
        service.create(
            token = token(uid = "host"),
            input =
                input(
                    type = EventType.OPEN_PLAY,
                    clubId = club.id,
                    minPoints = 2,
                    maxPoints = 20,
                    validityStart = start,
                    validityEnd = end,
                ),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()

        // A valid window within the global 1..10 policy is stored.
        val ok =
            service.create(
                token = token(uid = "host"),
                input =
                    input(
                        type = EventType.OPEN_PLAY,
                        clubId = club.id,
                        minPoints = 2,
                        maxPoints = 8,
                        validityStart = start,
                        validityEnd = end,
                    ),
            ).shouldBeRight()
        ok.event.type shouldBe EventType.OPEN_PLAY
        ok.event.minPointsPerMatch shouldBe 2
        ok.event.maxPointsPerMatch shouldBe 8
    }

    @Test
    fun `a budgeted event create rejects each further config breach (#403)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val start = LocalDate.now()

        fun attempt(
            min: Int,
            max: Int,
            end: LocalDate,
        ) = service.create(
            token = token(uid = "host"),
            input =
                input(
                    type = EventType.LEAGUE,
                    minPoints = min,
                    maxPoints = max,
                    validityStart = start,
                    validityEnd = end,
                ),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()

        // Non-positive points (bypasses the route's boundary check when the service is called directly).
        attempt(min = 0, max = 40, end = start.plusDays(10))
        attempt(min = 5, max = 0, end = start.plusDays(10))
        // Min greater than max.
        attempt(min = 40, max = 10, end = start.plusDays(10))
        // Max above LEAGUE's global maximum of 50.
        attempt(min = 5, max = 60, end = start.plusDays(10))
        // Validity end before start.
        attempt(min = 5, max = 40, end = start.minusDays(1))
    }

    @Test
    fun `a budgeted event create is rejected when the type has no global policy (#403)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        // Remove the LEAGUE global policy so validatePointsWindow hits the missing-policy path.
        transaction { exec(stmt = "DELETE FROM points_policies WHERE event_type = 'LEAGUE'") }
        service.create(
            token = token(uid = "host"),
            input =
                input(
                    type = EventType.LEAGUE,
                    minPoints = 5,
                    maxPoints = 40,
                    validityStart = LocalDate.now(),
                    validityEnd = LocalDate.now().plusDays(10),
                ),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `setPointsConfig updates a budgeted event and writes an Activity Log entry (#403)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val event =
            service.create(
                token = token(uid = "host"),
                input =
                    input(
                        type = EventType.LEAGUE,
                        participants = listOf(p1.id, p2.id),
                        minPoints = 5,
                        maxPoints = 40,
                        validityStart = LocalDate.now(),
                        validityEnd = LocalDate.now().plusDays(30),
                    ),
            ).shouldBeRight().event
        // An existing in-range fixture (20 ∈ [10,30]) exercises the "all designations still fit" path.
        matchRepo.createFixture(
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
                    eventId = event.id,
                    designatedPoints = 20,
                ),
        )

        val updated =
            service.setPointsConfig(
                token = token(uid = "host"),
                id = event.id,
                config = config(min = 10, max = 30, end = LocalDate.now().plusDays(60)),
            ).shouldBeRight()
        updated.event.minPointsPerMatch shouldBe 10
        updated.event.maxPointsPerMatch shouldBe 30

        AuditRepository()
            .list(actions = listOf(element = AuditAction.EVENT_POINTS_CONFIG_SET), limit = 10, offset = 0)
            .first
            .single()
            .action shouldBe AuditAction.EVENT_POINTS_CONFIG_SET
    }

    @Test
    fun `setPointsConfig accepts an OPEN_PLAY event within the global policy, but rejects out-of-range (#403)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input(type = EventType.OPEN_PLAY)).shouldBeRight().event
        // OPEN_PLAY now carries a config like the other types; 2..5 is within the global 1..10 window.
        val view =
            service.setPointsConfig(
                token = token(uid = "host"),
                id = event.id,
                config = config(min = 2, max = 5, end = LocalDate.now().plusDays(10)),
            ).shouldBeRight()
        view.event.minPointsPerMatch shouldBe 2
        view.event.maxPointsPerMatch shouldBe 5
        // A window beyond the global OPEN_PLAY max (10) is still rejected.
        service.setPointsConfig(
            token = token(uid = "host"),
            id = event.id,
            config = config(min = 2, max = 20, end = LocalDate.now().plusDays(10)),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `setPointsConfig rejects a window that excludes an existing fixture's designation (#403)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val event =
            service.create(
                token = token(uid = "host"),
                input =
                    input(
                        type = EventType.LEAGUE,
                        participants = listOf(p1.id, p2.id),
                        minPoints = 5,
                        maxPoints = 40,
                        validityStart = LocalDate.now(),
                        validityEnd = LocalDate.now().plusDays(30),
                    ),
            ).shouldBeRight().event
        // A fixture designating 40 points (at the top of the window).
        matchRepo.createFixture(
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
                    eventId = event.id,
                    designatedPoints = 40,
                ),
        )
        // Tightening the max to 30 would exclude the existing 40-point designation (above the new max).
        service.setPointsConfig(
            token = token(uid = "host"),
            id = event.id,
            config = config(min = 5, max = 30),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        // Raising the min to 45 also excludes it (now below the new min).
        service.setPointsConfig(
            token = token(uid = "host"),
            id = event.id,
            config = config(min = 45, max = 50),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `setPointsConfig is not-found for a missing event and forbidden across host ownership (#403)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "other", roles = setOf(Capability.PLAYER, Capability.HOST))
        // A CLUB_OWNER and an ADMINISTRATOR (neither the creator) may still edit — both authz arms.
        provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        service.setPointsConfig(token = token(uid = "host"), id = UUID.randomUUID(), config = config(min = 5, max = 40))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()

        val event =
            service.create(
                token = token(uid = "host"),
                input =
                    input(
                        type = EventType.LEAGUE,
                        minPoints = 5,
                        maxPoints = 40,
                        validityStart = LocalDate.now(),
                        validityEnd = LocalDate.now().plusDays(30),
                    ),
            ).shouldBeRight().event

        // Another plain host cannot edit; a club owner and an administrator can (isAdminOrOwner path).
        service.setPointsConfig(token = token(uid = "other"), id = event.id, config = config(min = 6, max = 30))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.setPointsConfig(token = token(uid = "owner"), id = event.id, config = config(min = 6, max = 30))
            .shouldBeRight()
        service.setPointsConfig(token = token(uid = "admin"), id = event.id, config = config(min = 7, max = 30))
            .shouldBeRight()
    }

    @Test
    fun `setPointsConfig rejects a window outside the global policy and a finalized event (#403)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event =
            service.create(
                token = token(uid = "host"),
                input =
                    input(
                        type = EventType.LEAGUE,
                        minPoints = 5,
                        maxPoints = 40,
                        validityStart = LocalDate.now(),
                        validityEnd = LocalDate.now().plusDays(30),
                    ),
            ).shouldBeRight().event

        // A window above LEAGUE's global max of 50 is rejected via validatePointsWindow.
        service.setPointsConfig(token = token(uid = "host"), id = event.id, config = config(min = 5, max = 60))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()

        // Once finalized, the config is frozen.
        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()
        service.setPointsConfig(token = token(uid = "host"), id = event.id, config = config(min = 6, max = 30))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    // --- Opt-in "award points" checkbox (#466). ---

    @Test
    fun `create with the award-points checkbox off stores no config, on validates against the global policy (#466)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        // Checkbox off — no config fields supplied → the event awards no points.
        val off = service.create(token = token(uid = "host"), input = input(type = EventType.LEAGUE)).shouldBeRight()
        off.event.minPointsPerMatch.shouldBeNull()
        off.event.pointValidityStart.shouldBeNull()

        // Checkbox on — a full config within LEAGUE's global 5..50 window is stored.
        val on =
            service.create(
                token = token(uid = "host"),
                input =
                    input(
                        type = EventType.LEAGUE,
                        minPoints = 5,
                        maxPoints = 40,
                        validityStart = LocalDate.now(),
                        validityEnd = LocalDate.now().plusDays(30),
                    ),
            ).shouldBeRight()
        on.event.minPointsPerMatch shouldBe 5

        // On but out of the global window → rejected. On but partial → rejected.
        service.create(
            token = token(uid = "host"),
            input =
                input(
                    type = EventType.LEAGUE,
                    minPoints = 1,
                    maxPoints = 40,
                    validityStart = LocalDate.now(),
                    validityEnd = LocalDate.now().plusDays(30),
                ),
        ).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.create(token = token(uid = "host"), input = input(type = EventType.LEAGUE, minPoints = 5))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `setPointsConfig toggles the award-points checkbox on then off, clearing config and cascading to fixtures (#466)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        // Start with the checkbox OFF (no config).
        val event =
            service.create(
                token = token(uid = "host"),
                input = input(type = EventType.LEAGUE, participants = listOf(p1.id, p2.id)),
            ).shouldBeRight().event
        event.minPointsPerMatch.shouldBeNull()

        // Tick ON — sets a valid config.
        val on =
            service.setPointsConfig(
                token = token(uid = "host"),
                id = event.id,
                config = config(min = 10, max = 30, end = LocalDate.now().plusDays(30)),
            ).shouldBeRight()
        on.event.minPointsPerMatch shouldBe 10

        // Seed a designated fixture so the OFF cascade has something to null out.
        matchRepo.createFixture(
            command =
                CreateFixtureCommand(
                    matchFormat = TeamType.SINGLES,
                    matchType = MatchType.OPEN_PLAY,
                    matchDate = LocalDate.now(),
                    team1UserIds = listOf(element = p1.id),
                    team2UserIds = listOf(element = p2.id),
                    team1Name = "p1",
                    team2Name = "p2",
                    createdBy = host.id,
                    eventId = event.id,
                    designatedPoints = 20,
                ),
        )

        // Un-tick OFF (null config) — clears the config AND cascades to null out the fixture designation.
        val off = service.setPointsConfig(token = token(uid = "host"), id = event.id, config = null).shouldBeRight()
        off.event.minPointsPerMatch.shouldBeNull()
        off.event.pointValidityStart.shouldBeNull()
        matchRepo.listByEvent(eventId = event.id).forEach { it.designatedPoints.shouldBeNull() }
    }

    // --- Finalize-time awarding (#403 Phase D). ---

    private val awardRepo = RankingPointRepository()

    private fun budgetedEvent(
        hostUid: String,
        participants: List<UUID>,
        type: EventType = EventType.LEAGUE,
        validityDays: Long = 30,
    ) = service.create(
        token = token(uid = hostUid),
        input =
            input(
                type = type,
                participants = participants,
                // 10..50 satisfies both LEAGUE (5..50) and TOURNAMENT (10..500) global policies.
                minPoints = 10,
                maxPoints = 50,
                validityStart = LocalDate.now(),
                validityEnd = LocalDate.now().plusDays(validityDays),
            ),
    ).shouldBeRight().event

    /** Seed a COMPLETED singles fixture designating [designated], won by team1 (p1). */
    private fun seedCompletedFixture(
        eventId: UUID,
        host: User,
        p1: User,
        p2: User,
        designated: Int?,
        format: TeamType = TeamType.SINGLES,
        team1UserIds: List<UUID> = listOf(element = p1.id),
        team2UserIds: List<UUID> = listOf(element = p2.id),
    ): Match {
        val match =
            matchRepo.createFixture(
                command =
                    CreateFixtureCommand(
                        matchFormat = format,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = LocalDate.now(),
                        team1UserIds = team1UserIds,
                        team2UserIds = team2UserIds,
                        team1Name = "t1",
                        team2Name = "t2",
                        createdBy = host.id,
                        eventId = eventId,
                        designatedPoints = designated,
                    ),
            )
        recordResult(match = match)
        return matchRepo.findById(matchId = match.id).shouldBeRight()
    }

    private fun rate(
        userId: UUID,
        level: String,
    ) = RatingRepository().setRating(userId = userId, rating = BigDecimal(level), level = level)

    @Test
    fun `finalizing a LEAGUE event awards each winner the full designated points with the event window (#403)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2, designated = 30)

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // SINGLES → exactly one award row (the winner p1), the full 30 points.
        awardRepo.listByUser(userId = p2.id) shouldHaveSize 0
        val award = awardRepo.listByUser(userId = p1.id).single()
        award.points shouldBe BigDecimal("30.0000")
        award.band shouldBe "4.0"
        award.eventId shouldBe event.id
        award.sourceId shouldBe event.id.toString()
        award.pointClass shouldBe org.skopeo.model.PointClass.SEASONAL_TOURNAMENT_1M
        // Validity from the event window: start-of-day start, exclusive next-day after the end.
        award.validFrom shouldBe LocalDate.now().atStartOfDay()
        award.validUntil shouldBe LocalDate.now().plusDays(31).atStartOfDay()
        // The award is audited.
        AuditRepository().list(actions = listOf(element = AuditAction.EVENT_POINTS_AWARDED), limit = 10, offset = 0)
            .first.single().details["totalPoints"] shouldBe "30"
    }

    @Test
    fun `finalize records one per-player audit entry targeting each awarded winner (#471)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        // Two SINGLES fixtures, each with a distinct winner (team1 wins), so both players are awarded.
        val matchA = seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2, designated = 30)
        val matchB =
            seedCompletedFixture(
                eventId = event.id,
                host = host,
                p1 = p2,
                p2 = p1,
                designated = 20,
                team1UserIds = listOf(element = p2.id),
                team2UserIds = listOf(element = p1.id),
            )

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // One RANKING_POINTS_AWARDED entry per awarded winner, each targeting the player (USER), with the
        // finalizer as actor and the points/match in the detail (#471).
        val entries =
            AuditRepository()
                .list(actions = listOf(element = AuditAction.RANKING_POINTS_AWARDED), limit = 10, offset = 0)
                .first
        entries shouldHaveSize 2
        entries.forEach { it.entityType shouldBe AuditEntityType.USER }
        entries.forEach { it.actorUserId shouldBe host.id }
        entries.map { it.entityId }.toSet() shouldBe setOf(p1.id, p2.id)

        val p1Entry = entries.single { it.entityId == p1.id }
        p1Entry.details["points"] shouldBe "30"
        p1Entry.details["matchId"] shouldBe matchA.id.toString()
        p1Entry.details["matchPublicCode"] shouldBe matchA.publicCode
        p1Entry.details["eventId"] shouldBe event.id.toString()
        p1Entry.details["band"] shouldBe "4.0"

        val p2Entry = entries.single { it.entityId == p2.id }
        p2Entry.details["points"] shouldBe "20"
        p2Entry.details["matchId"] shouldBe matchB.id.toString()
    }

    @Test
    fun `finalize records the granting match id on each award, for the profile points audit (#448)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        val match = seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2, designated = 30)

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // The winner's award is tagged with the exact fixture that granted it (#448) — the audit links
        // straight to that match — while still carrying the event link for the fallback.
        val award = awardRepo.listByUser(userId = p1.id).single()
        award.matchId shouldBe match.id
        award.eventId shouldBe event.id
        // And the active-awards read surfaces it.
        awardRepo.listActiveByUser(userId = p1.id, asOf = LocalDateTime.now()).single().matchId shouldBe match.id
    }

    @Test
    fun `finalizing an OPEN_PLAY event awards each winner the designated points (unify)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        // OPEN_PLAY now carries a config (global 1..10) and awards on finalize like the other types.
        val event =
            service.create(
                token = token(uid = "host"),
                input =
                    input(
                        type = EventType.OPEN_PLAY,
                        participants = listOf(p1.id, p2.id),
                        minPoints = 1,
                        maxPoints = 10,
                        validityStart = LocalDate.now(),
                        validityEnd = LocalDate.now().plusDays(30),
                    ),
            ).shouldBeRight().event
        seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2, designated = 8)

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // SINGLES → one award row (winner p1), the full 8 designated points; the loser gets nothing.
        awardRepo.listByUser(userId = p2.id) shouldHaveSize 0
        val award = awardRepo.listByUser(userId = p1.id).single()
        award.points shouldBe BigDecimal("8.0000")
        award.band shouldBe "4.0"
        award.eventId shouldBe event.id
    }

    @Test
    fun `finalizing a doubles fixture writes one full-amount row per winning-team member (#403)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val a = provision(uid = "a")
        val b = provision(uid = "b")
        val c = provision(uid = "c")
        val d = provision(uid = "d")
        listOf(a, b, c, d).forEach { rate(userId = it.id, level = "3.5") }
        val event = budgetedEvent(hostUid = "host", participants = listOf(a.id, b.id, c.id, d.id), type = EventType.TOURNAMENT)
        seedCompletedFixture(
            eventId = event.id,
            host = host,
            p1 = a,
            p2 = c,
            designated = 40,
            format = TeamType.DOUBLES,
            team1UserIds = listOf(a.id, b.id),
            team2UserIds = listOf(c.id, d.id),
        )

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // Both winning-team members get the FULL 40 (two rows of 40, not a split).
        awardRepo.listByUser(userId = a.id).single().points shouldBe BigDecimal("40.0000")
        awardRepo.listByUser(userId = b.id).single().points shouldBe BigDecimal("40.0000")
        // Losers get nothing.
        awardRepo.listByUser(userId = c.id) shouldHaveSize 0
        awardRepo.listByUser(userId = d.id) shouldHaveSize 0
    }

    @Test
    fun `finalizing an event whose fixtures have no designated or no winner awards nothing (#403)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        // A completed fixture with no designation → nothing awarded (clubless events skip designation here).
        val open = service.create(token = token(uid = "host"), input = input(participants = listOf(p1.id, p2.id))).shouldBeRight().event
        seedCompletedFixture(eventId = open.id, host = host, p1 = p1, p2 = p2, designated = null)
        service.finalize(token = token(uid = "host"), id = open.id).shouldBeRight()
        awardRepo.listByUser(userId = p1.id) shouldHaveSize 0

        // A budgeted event whose only fixture is still SCHEDULED (no winner) → nothing awarded.
        val league = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        matchRepo.createFixture(
            command =
                CreateFixtureCommand(
                    matchFormat = TeamType.SINGLES,
                    matchType = MatchType.OPEN_PLAY,
                    matchDate = LocalDate.now(),
                    team1UserIds = listOf(element = p1.id),
                    team2UserIds = listOf(element = p2.id),
                    team1Name = "t1",
                    team2Name = "t2",
                    createdBy = host.id,
                    eventId = league.id,
                    designatedPoints = 30,
                ),
        )
        service.finalize(token = token(uid = "host"), id = league.id).shouldBeRight()
        awardRepo.listByUser(userId = p1.id) shouldHaveSize 0
    }

    @Test
    fun `a winner with no rating is skipped and not awarded (#403)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        // p1 wins but has no rating → no band to tag → the award is skipped.
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2, designated = 30)

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()
        awardRepo.listByUser(userId = p1.id) shouldHaveSize 0
    }

    @Test
    fun `a budgeted event missing its validity window awards nothing (defensive, #403)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2, designated = 30)
        // Null out the point-validity window (a legacy / pre-config budgeted event) → the awarder's
        // defensive null-window guard returns without awarding.
        transaction {
            EventsTable.update(where = { EventsTable.id eq event.id }) {
                it[pointValidityStart] = null
                it[pointValidityEnd] = null
            }
        }

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()
        awardRepo.listByUser(userId = p1.id) shouldHaveSize 0
    }

    @Test
    fun `a completed fixture with no designation in a budgeted event awards nothing (#403)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        // A budgeted event whose completed, won fixture carries NO designation → the designation filter
        // drops it (exercises the null-designation arm without an early type/config return).
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2, designated = null)

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()
        awardRepo.listByUser(userId = p1.id) shouldHaveSize 0
    }

    @Test
    fun `the award's point class maps the event window length to the nearest class (#403)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        fun finalizeWithWindow(
            days: Long,
            p1uid: String,
            p2uid: String,
        ): org.skopeo.model.PointClass {
            val p1 = provision(uid = p1uid)
            val p2 = provision(uid = p2uid)
            rate(userId = p1.id, level = "4.0")
            val event =
                budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id), type = EventType.TOURNAMENT, validityDays = days)
            seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2, designated = 20)
            service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()
            return awardRepo.listByUser(userId = p1.id).single().pointClass
        }

        // ≤31d → 1M, ≤92d → 3M, ≤184d → 6M, else ANNUAL — all four when-arms.
        finalizeWithWindow(days = 20, p1uid = "a1", p2uid = "a2") shouldBe org.skopeo.model.PointClass.SEASONAL_TOURNAMENT_1M
        finalizeWithWindow(days = 60, p1uid = "b1", p2uid = "b2") shouldBe org.skopeo.model.PointClass.SEASONAL_TOURNAMENT_3M
        finalizeWithWindow(days = 120, p1uid = "c1", p2uid = "c2") shouldBe org.skopeo.model.PointClass.SEASONAL_TOURNAMENT_6M
        finalizeWithWindow(days = 300, p1uid = "d1", p2uid = "d2") shouldBe org.skopeo.model.PointClass.ANNUAL_TOURNAMENT
    }

    @Test
    fun `the second team's win path is awarded when team2 is the winner (#403)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        val match =
            matchRepo.createFixture(
                command =
                    CreateFixtureCommand(
                        matchFormat = TeamType.SINGLES,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = LocalDate.now(),
                        team1UserIds = listOf(element = p1.id),
                        team2UserIds = listOf(element = p2.id),
                        team1Name = "t1",
                        team2Name = "t2",
                        createdBy = host.id,
                        eventId = event.id,
                        designatedPoints = 30,
                    ),
            )
        // team2 (p2) wins → the winningUserIds team2 arm is exercised.
        matchRepo.addResult(
            matchId = match.id,
            sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 4, team2Games = 6, winnerTeamId = match.team2.teamId)),
            winnerTeamId = match.team2.teamId,
            recordedBy = host.id,
            completedAt = LocalDateTime.now(),
        )

        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()
        awardRepo.listByUser(userId = p2.id).single().points shouldBe BigDecimal("30.0000")
        awardRepo.listByUser(userId = p1.id) shouldHaveSize 0
    }
}
