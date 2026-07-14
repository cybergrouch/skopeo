// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.event

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.CreateClubCommand
import org.skopeo.model.CreateFixtureCommand
import org.skopeo.model.EventParticipantStatus
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
import org.skopeo.repository.ClubRepository
import org.skopeo.repository.EventRepository
import org.skopeo.repository.EventsTable
import org.skopeo.repository.MatchRepository
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

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    // Default to a currently-running event (ends a week out) so host data-entry stays allowed; the
    // expired-event tests (#310) pass explicit past dates.
    private fun input(
        name: String = "Spring Open",
        start: String = LocalDate.now().toString(),
        end: String = LocalDate.now().plusDays(7).toString(),
        participants: List<UUID> = emptyList(),
        clubId: UUID? = null,
    ) = CreateEventInput(
        name = name,
        startDate = LocalDate.parse(start),
        endDate = LocalDate.parse(end),
        participantIds = participants,
        clubId = clubId,
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
        view.club.shouldBeNull() // clubless by default
    }

    @Test
    fun `an event can be created under a club, and an unknown club is rejected (#313)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val club = clubs.create(command = CreateClubCommand(name = "Downtown TC", createdBy = host.id))

        val view = service.create(token = token(uid = "host"), input = input(clubId = club.id)).shouldBeRight()
        view.club?.id shouldBe club.id
        view.club?.name shouldBe "Downtown TC"
        view.event.clubId shouldBe club.id

        // An unknown club is rejected at create.
        service.create(token = token(uid = "host"), input = input(clubId = UUID.randomUUID()))
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
        val underClub = service.create(token = token(uid = "host"), input = input(clubId = club.id)).shouldBeRight()
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
    fun `a host renames their own event, trimming the name`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = service.create(token = token(uid = "host"), input = input(name = "Spring Open")).shouldBeRight().event

        val renamed = service.rename(token = token(uid = "host"), id = event.id, name = "  Summer Classic  ").shouldBeRight()

        renamed.event.name shouldBe "Summer Classic"
        events.findById(id = event.id)!!.name shouldBe "Summer Classic"
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
}
