// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.club

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.dto.club.toResponse
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.CreateEventCommand
import org.skopeo.model.CreateFixtureCommand
import org.skopeo.model.EventType
import org.skopeo.model.MatchSetResult
import org.skopeo.model.MatchType
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.TeamType
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.ClubRepository
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class ClubServiceTest {
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
    private val matchRepo = MatchRepository()
    private val service = ClubService(clubs = clubs, users = users)

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

    @Test
    fun `an admin creates a club and it appears in the list`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))

        val created = service.create(token = token(uid = "admin"), name = "  Downtown TC  ").shouldBeRight()
        created.name shouldBe "Downtown TC" // trimmed
        created.owners shouldHaveSize 0

        val list = service.list(token = token(uid = "admin")).shouldBeRight()
        list shouldHaveSize 1
        list.single().name shouldBe "Downtown TC"
    }

    @Test
    fun `an admin assigns and removes owners, resolving display names`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val owner1 = provision(uid = "owner1", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val owner2 = provision(uid = "owner2", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val club = service.create(token = token(uid = "admin"), name = "West End").shouldBeRight()

        val withOne = service.assignOwner(token = token(uid = "admin"), clubId = club.id, userId = owner1.id).shouldBeRight()
        withOne.owners shouldHaveSize 1
        withOne.owners.single().let {
            it.userId shouldBe owner1.id
            it.displayName shouldBe "owner1"
            it.publicCode shouldBe owner1.publicCode
        }

        // Re-assigning the same owner is idempotent.
        service.assignOwner(token = token(uid = "admin"), clubId = club.id, userId = owner1.id).shouldBeRight().owners shouldHaveSize 1

        service.assignOwner(token = token(uid = "admin"), clubId = club.id, userId = owner2.id).shouldBeRight().owners shouldHaveSize 2

        val afterRemove = service.removeOwner(token = token(uid = "admin"), clubId = club.id, userId = owner1.id).shouldBeRight()
        afterRemove.owners shouldHaveSize 1
        afterRemove.owners.single().userId shouldBe owner2.id
    }

    @Test
    fun `creating and owner management is administrator-only`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        service.create(token = token(uid = "host"), name = "X").shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `an unprovisioned caller is forbidden from admin-only actions (#362)`() {
        // A token whose uid resolves to no user hits the null-caller arm of requireAdmin.
        service.create(token = token(uid = "ghost"), name = "X").shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `listing is readable by staff but not plain players (#313)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "player")
        service.create(token = token(uid = "admin"), name = "Club").shouldBeRight()

        // A HOST (event creator) may read the list to pick a club…
        service.list(token = token(uid = "host")).shouldBeRight() shouldHaveSize 1
        // …but a plain player and an unprovisioned caller cannot.
        service.list(token = token(uid = "player")).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.list(token = token(uid = "ghost")).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `create rejects a blank name`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        service.create(token = token(uid = "admin"), name = "   ").shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `an admin renames a club, trimming the name, and the list reflects it (#325)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val club = service.create(token = token(uid = "admin"), name = "Old Name").shouldBeRight()

        val renamed = service.rename(token = token(uid = "admin"), clubId = club.id, name = "  New Name  ").shouldBeRight()
        renamed.name shouldBe "New Name"
        service.list(token = token(uid = "admin")).shouldBeRight().single().name shouldBe "New Name"
    }

    @Test
    fun `rename validates the name, checks existence, and is administrator-only (#325)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val club = service.create(token = token(uid = "admin"), name = "Club").shouldBeRight()

        // Blank name → Validation.
        service.rename(token = token(uid = "admin"), clubId = club.id, name = "   ")
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        // Unknown club → NotFound.
        service.rename(token = token(uid = "admin"), clubId = UUID.randomUUID(), name = "X")
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
        // Non-admin → Forbidden.
        service.rename(token = token(uid = "host"), clubId = club.id, name = "X")
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `an admin soft-deletes a club, it drops from the list, and a second delete is not-found (#325)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val club = service.create(token = token(uid = "admin"), name = "Doomed").shouldBeRight()

        service.delete(token = token(uid = "admin"), clubId = club.id).shouldBeRight()
        service.list(token = token(uid = "admin")).shouldBeRight() shouldHaveSize 0
        // Idempotent guard: deleting again (now inactive) is a not-found.
        service.delete(token = token(uid = "admin"), clubId = club.id)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `deleting a club cascades to soft-delete its events and matches, kept traceable (#325)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val club = service.create(token = token(uid = "admin"), name = "Doomed").shouldBeRight()
        val event =
            events.create(
                command =
                    CreateEventCommand(
                        name = "Meet",
                        startDate = LocalDate.now(),
                        endDate = LocalDate.now().plusDays(1),
                        participantIds = listOf(p1.id, p2.id),
                        createdBy = admin.id,
                        clubId = club.id,
                    ),
            )
        val match =
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
                        createdBy = admin.id,
                        eventId = event.id,
                    ),
            )

        service.delete(token = token(uid = "admin"), clubId = club.id).shouldBeRight()

        // The event and its match drop out of active lists…
        events.findById(id = event.id)!!.isActive shouldBe false
        // …but stay traceable by public code, flagged not-active (#325).
        events.findByPublicCode(code = event.publicCode)!!.isActive shouldBe false
        matchRepo.findByPublicCode(code = match.publicCode)!!.isActive shouldBe false
    }

    @Test
    fun `deleting a club excludes its event's match from player match history via the container join (#497)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val club = service.create(token = token(uid = "admin"), name = "Doomed2").shouldBeRight()
        val event =
            events.create(
                command =
                    CreateEventCommand(
                        name = "Meet2",
                        startDate = LocalDate.now(),
                        endDate = LocalDate.now().plusDays(1),
                        participantIds = listOf(p1.id, p2.id),
                        createdBy = admin.id,
                        clubId = club.id,
                    ),
            )
        val underClub =
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
                        createdBy = admin.id,
                        eventId = event.id,
                    ),
            )
        matchRepo.addResult(
            matchId = underClub.id,
            sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 0, winnerTeamId = underClub.team1.teamId)),
            winnerTeamId = underClub.team1.teamId,
            recordedBy = admin.id,
            completedAt = LocalDateTime.now(),
        )
        // A standalone (clubless) match must still surface after the club delete.
        val standalone =
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
                        createdBy = admin.id,
                    ),
            )

        // Deleting the club cascades is_active=false down through its event onto the match.
        service.delete(token = token(uid = "admin"), clubId = club.id).shouldBeRight()

        val history = matchRepo.listByUser(userId = p1.id).map { it.id }
        history shouldContain standalone.id
        history shouldNotContain underClub.id
        // Win/loss likewise drops the only decided match (the container-deleted one), leaving p1 with no
        // decided record at all — absent from the aggregate map.
        matchRepo.winLossByUsers(userIds = listOf(element = p1.id)).containsKey(key = p1.id) shouldBe false
    }

    @Test
    fun `delete checks existence and is administrator-only (#325)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val club = service.create(token = token(uid = "admin"), name = "Club").shouldBeRight()

        service.delete(token = token(uid = "admin"), clubId = UUID.randomUUID())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
        service.delete(token = token(uid = "host"), clubId = club.id)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `assignOwner is not-found for a missing club and validation for an unknown user`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val club = service.create(token = token(uid = "admin"), name = "Club").shouldBeRight()

        service.assignOwner(token = token(uid = "admin"), clubId = UUID.randomUUID(), userId = owner.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
        service.assignOwner(token = token(uid = "admin"), clubId = club.id, userId = UUID.randomUUID())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `removeOwner is a not-found for a missing club`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        service.removeOwner(token = token(uid = "admin"), clubId = UUID.randomUUID(), userId = owner.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `assignOwner rejects an inactive user`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        users.deactivate(id = owner.id).shouldBeRight()
        val club = service.create(token = token(uid = "admin"), name = "Club").shouldBeRight()

        service.assignOwner(token = token(uid = "admin"), clubId = club.id, userId = owner.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `assignOwner rejects a user without the CLUB_OWNER capability (#317)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val plain = provision(uid = "plain", roles = setOf(element = Capability.PLAYER))
        val club = service.create(token = token(uid = "admin"), name = "Club").shouldBeRight()

        service.assignOwner(token = token(uid = "admin"), clubId = club.id, userId = plain.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `publicByCode returns the club name and splits its events into upcoming and past (#327)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provision(uid = "p1")
        val club = service.create(token = token(uid = "admin"), name = "Downtown TC").shouldBeRight()

        val today = LocalDate.now()
        // A past event (ended yesterday), a still-running one (ends today), and a future one.
        events.create(
            command =
                CreateEventCommand(
                    name = "Last Week",
                    startDate = today.minusDays(7),
                    endDate = today.minusDays(1),
                    participantIds = listOf(element = p1.id),
                    createdBy = admin.id,
                    clubId = club.id,
                ),
        )
        events.create(
            command =
                CreateEventCommand(
                    name = "Running Now",
                    startDate = today.minusDays(1),
                    endDate = today,
                    participantIds = listOf(element = p1.id),
                    createdBy = admin.id,
                    clubId = club.id,
                ),
        )
        events.create(
            command =
                CreateEventCommand(
                    name = "Next Month",
                    startDate = today.plusDays(30),
                    endDate = today.plusDays(31),
                    participantIds = listOf(element = p1.id),
                    createdBy = admin.id,
                    clubId = club.id,
                ),
        )

        val view = service.publicByCode(code = club.publicCode).shouldBeRight()
        view.name shouldBe "Downtown TC"
        view.isActive shouldBe true
        // Ends-today and future are upcoming, soonest-first; ended-yesterday is past.
        view.upcoming.map { it.name } shouldBe listOf("Running Now", "Next Month")
        view.past.map { it.name } shouldBe listOf(element = "Last Week")
    }

    @Test
    fun `publicByCode is anonymous (no roster PII) and not-found for an unknown code (#327)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val club = service.create(token = token(uid = "admin"), name = "Empty Club").shouldBeRight()

        // A club with no events resolves with empty splits.
        val view = service.publicByCode(code = club.publicCode).shouldBeRight()
        view.upcoming shouldHaveSize 0
        view.past shouldHaveSize 0

        service.publicByCode(code = "ZZZZZZ").shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    /** A points-carrying event under [clubId], dated in the past when [past] else upcoming. */
    private fun pointsEvent(
        clubId: UUID,
        createdBy: UUID,
        name: String,
        type: EventType,
        past: Boolean,
    ): org.skopeo.model.Event {
        val today = LocalDate.now()
        val start = if (past) today.minusDays(7) else today.plusDays(1)
        val end = if (past) today.minusDays(1) else today.plusDays(2)
        return events.create(
            command =
                CreateEventCommand(
                    name = name,
                    startDate = start,
                    endDate = end,
                    participantIds = listOf(element = createdBy),
                    createdBy = createdBy,
                    clubId = clubId,
                    type = type,
                    minPointsPerMatch = 5,
                    maxPointsPerMatch = 50,
                    pointValidityStart = start,
                    pointValidityEnd = end.plusDays(30),
                ),
        )
    }

    private fun designatedFixture(
        eventId: UUID,
        createdBy: UUID,
        p1: UUID,
        p2: UUID,
        designated: Int,
    ) = matchRepo.createFixture(
        command =
            CreateFixtureCommand(
                matchFormat = TeamType.SINGLES,
                matchType = MatchType.OPEN_PLAY,
                matchDate = LocalDate.now(),
                team1UserIds = listOf(element = p1),
                team2UserIds = listOf(element = p2),
                team1Name = "t1",
                team2Name = "t2",
                createdBy = createdBy,
                eventId = eventId,
                designatedPoints = designated,
            ),
    )

    private fun awardLinked(
        userId: UUID,
        eventId: UUID,
        grantedBy: UUID,
        points: String,
    ) = org.skopeo.repository.RankingPointRepository().award(
        write =
            org.skopeo.model.RankingPointAwardWrite(
                userId = userId,
                points = java.math.BigDecimal(points),
                pointClass = org.skopeo.model.PointClass.SEASONAL_TOURNAMENT_1M,
                sourceType = org.skopeo.model.PointSourceType.INTERNAL,
                sourceId = eventId.toString(),
                band = "4.0",
                sex = "Male",
                reason = null,
                validFrom = java.time.LocalDateTime.now().minusDays(1),
                validUntil = java.time.LocalDateTime.now().plusDays(30),
                status = org.skopeo.model.AwardStatus.ACTIVE,
                revokesAwardId = null,
                grantedBy = grantedBy,
                awardedAt = java.time.LocalDateTime.now(),
                eventId = eventId,
            ),
    )

    @Test
    fun `publicByCode carries eventType and per-event designated and awarded points (#403)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val club = service.create(token = token(uid = "admin"), name = "Downtown TC").shouldBeRight()

        // A non-finalized upcoming LEAGUE event with a designated fixture → shows designated, awarded 0.
        val upcoming =
            pointsEvent(clubId = club.id, createdBy = admin.id, name = "Upcoming League", type = EventType.LEAGUE, past = false)
        designatedFixture(eventId = upcoming.id, createdBy = admin.id, p1 = p1.id, p2 = p2.id, designated = 30)

        // A finalized past TOURNAMENT event with an active award linked → carries awarded points.
        val past =
            pointsEvent(clubId = club.id, createdBy = admin.id, name = "Past Cup", type = EventType.TOURNAMENT, past = true)
        events.finalize(id = past.id, finalizedAt = java.time.LocalDateTime.now(), finalizedBy = admin.id)
        awardLinked(userId = p1.id, eventId = past.id, grantedBy = admin.id, points = "40")

        val view = service.publicByCode(code = club.publicCode).shouldBeRight()
        val up = view.upcoming.single { it.name == "Upcoming League" }
        up.eventType shouldBe EventType.LEAGUE
        up.designatedPoints shouldBe 30
        up.awardedPoints shouldBe 0
        val pastEvent = view.past.single { it.name == "Past Cup" }
        pastEvent.eventType shouldBe EventType.TOURNAMENT
        pastEvent.awardedPoints shouldBe 40
    }

    @Test
    fun `the public club response never surfaces club utilization, only per-event points (#403)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        val club = service.create(token = token(uid = "admin"), name = "Downtown TC").shouldBeRight()
        val event =
            pointsEvent(clubId = club.id, createdBy = admin.id, name = "League", type = EventType.LEAGUE, past = false)
        designatedFixture(eventId = event.id, createdBy = admin.id, p1 = p1.id, p2 = p2.id, designated = 30)

        val view = service.publicByCode(code = club.publicCode).shouldBeRight()
        val json =
            kotlinx.serialization.json.Json.encodeToString(
                serializer = org.skopeo.dto.club.ClubPublicResponse.serializer(),
                value = view.toResponse(),
            )
        // Utilization figures must not leak onto the anonymous public surface.
        json shouldNotContain "budgeted"
        json shouldNotContain "allocated"
        json shouldNotContain "free"
        json shouldNotContain "utilization"
        // But the per-event points are present.
        json shouldContain "designatedPoints"
    }

    @Test
    fun `an owner without a display name is shown by public code`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val owner =
            users.provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = "nameless",
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = "nameless", isPrimary = true),
                        names = emptyList(),
                        capabilities = setOf(element = Capability.CLUB_OWNER),
                    ),
            )
        val club = service.create(token = token(uid = "admin"), name = "Club").shouldBeRight()

        service.assignOwner(token = token(uid = "admin"), clubId = club.id, userId = owner.id)
            .shouldBeRight()
            .owners
            .single()
            .let {
                it.displayName.shouldBeNull()
                it.publicCode shouldBe owner.publicCode
            }
    }
}
