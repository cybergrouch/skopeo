// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.club

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.dto.match.MatchResultRequest
import org.skopeo.common.dto.match.SetScoreRequest
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.club.toDomain
import org.skopeo.domain.mapper.entity.event.toDomain
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.Club
import org.skopeo.domain.model.CreateClubCommand
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.Event
import org.skopeo.domain.model.Match
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.event.CreateEventInput
import org.skopeo.domain.service.event.EventService
import org.skopeo.domain.service.event.EventTeamService
import org.skopeo.domain.service.match.FixtureInput
import org.skopeo.domain.service.match.MatchService
import org.skopeo.domain.service.rating.RatingAssembler
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.ClubRepository
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Club-scoped event/match authorization (#789).
 *
 * The rule under test, per operation rather than only on create:
 *
 * ```
 * mayOrganize(caller, event) = ADMINISTRATOR
 *                            OR caller is a named owner of event.clubId
 *                            OR event.createdBy == caller.id   (grandfathered; also the clubless rule)
 * ```
 *
 * The widening half is the club arm: before #789 a co-owner could not touch their own club's event unless
 * they personally created it. The tightening half is filing: creating (or re-filing) an event under a club
 * you do not own used to succeed.
 *
 * HOST and CLUB_OWNER are deliberately equivalent here (decision 1 on #789) — the capabilities differ in
 * what they unlock elsewhere, not in their reach over a club's events.
 */
class ClubScopedEventAuthzTest {
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
    private val ratings = RatingAssembler()
    private val access = ClubAccess(clubs = clubs)
    private val service = EventService(events = events, users = users)
    private val teamService = EventTeamService()
    private val matchService = MatchService(matches = matchRepo, ratings = ratings, users = users)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    // --- fixtures ---------------------------------------------------------

    private fun provision(
        uid: String,
        roles: Set<Capability> = setOf(element = Capability.PLAYER),
        rated: Boolean = false,
    ): User {
        val user =
            users.provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid,
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                        capabilities = roles,
                    ),
            ).toDomain()
        if (rated) {
            ratings.setRating(userId = user.id, rating = BigDecimal("4.0"), level = "4.0")
        }
        return user
    }

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    /** A club with [owners] recorded in club_owners — the rows authorization actually reads. */
    private fun club(
        name: String,
        vararg owners: User,
    ): Club =
        clubs
            .create(command = CreateClubCommand(name = name, createdBy = owners.first().id))
            .toDomain()
            .also { created -> owners.forEach { clubs.addOwner(clubId = created.id, userId = it.id) } }

    private fun eventInput(
        name: String = "Spring Open",
        clubId: UUID? = null,
        participants: List<UUID> = emptyList(),
    ) = CreateEventInput(
        name = name,
        startDate = LocalDate.now(),
        endDate = LocalDate.now().plusDays(7),
        participantIds = participants,
        clubId = clubId,
    )

    /** An event filed under [clubId] created by [creatorUid], resolved back to the domain for its ids. */
    private fun eventBy(
        creatorUid: String,
        clubId: UUID?,
        participants: List<UUID> = emptyList(),
        name: String = "Spring Open",
    ): Event =
        service
            .create(token = token(uid = creatorUid), input = eventInput(name = name, clubId = clubId, participants = participants))
            .shouldBeRight()
            .let { events.findById(id = UUID.fromString(it.id))!!.toDomain() }

    /** A scheduled fixture seeded straight through the repository, so setup never trips the gate under test. */
    private fun fixture(
        event: Event,
        creator: User,
        p1: User,
        p2: User,
        date: LocalDate = LocalDate.parse("2026-03-02"),
    ): Match =
        matchRepo
            .createFixture(
                command =
                    CreateFixtureCommand(
                        matchFormat = TeamType.SINGLES,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = date,
                        team1UserIds = listOf(element = p1.id),
                        team2UserIds = listOf(element = p2.id),
                        team1Name = "p1",
                        team2Name = "p2",
                        createdBy = creator.id,
                        eventId = event.id,
                    ),
            ).toDomain()

    private fun straightSets() =
        MatchResultRequest(
            sets =
                listOf(
                    SetScoreRequest(team1Games = 6, team2Games = 4),
                    SetScoreRequest(team1Games = 6, team2Games = 3),
                ),
        )

    // --- the predicate itself --------------------------------------------

    @Test
    fun `mayOrganize allows an administrator, a named owner of the event's club, and the creator (#789)`() {
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val creator = provision(uid = "creator", roles = setOf(Capability.PLAYER, Capability.HOST))
        val stranger = provision(uid = "stranger", roles = setOf(Capability.PLAYER, Capability.HOST))
        val owned = club("Downtown TC", owner)
        val event = eventBy(creatorUid = "admin", clubId = owned.id).copy(createdBy = creator.id)

        access.mayOrganize(caller = admin, event = event).shouldBeTrue()
        access.mayOrganize(caller = owner, event = event).shouldBeTrue()
        access.mayOrganize(caller = creator, event = event).shouldBeTrue()
        access.mayOrganize(caller = stranger, event = event).shouldBeFalse()

        // A clubless event has no ownership anchor: only the administrator and the creator reach it.
        val clubless = event.copy(clubId = null)
        access.mayOrganize(caller = admin, event = clubless).shouldBeTrue()
        access.mayOrganize(caller = owner, event = clubless).shouldBeFalse()
        access.mayOrganize(caller = creator, event = clubless).shouldBeTrue()
    }

    @Test
    fun `ownedClubIds returns every club the caller is a named owner of, and none otherwise (#789)`() {
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val stranger = provision(uid = "stranger", roles = setOf(Capability.PLAYER, Capability.HOST))
        val a = club("Downtown TC", owner)
        val b = club("West End", owner)
        club("Northside", stranger)

        access.ownedClubIds(callerId = owner.id) shouldBe setOf(a.id, b.id)
        access.ownsClub(callerId = owner.id, clubId = a.id).shouldBeTrue()
        access.ownsClub(callerId = stranger.id, clubId = a.id).shouldBeFalse()
        // A clubless event is never "owned" by anyone — the creator clause is what covers it.
        access.ownsClub(callerId = owner.id, clubId = null).shouldBeFalse()
    }

    // --- create: the one tightening --------------------------------------

    @Test
    fun `creating an event under a club you do not own is refused, and under one you own succeeds (#789)`() {
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val theirs = club("Downtown TC", owner)
        val ours = club("West End", host)

        // Succeeded before #789: a host could file under any club at all.
        service
            .create(token = token(uid = "host"), input = eventInput(clubId = theirs.id))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        // A club they DO own is fine — a plain HOST has the same reach as a CLUB_OWNER here (decision 1).
        service.create(token = token(uid = "host"), input = eventInput(clubId = ours.id)).shouldBeRight()
        // And a clubless ("Open") event is unchanged: any staff caller may file one.
        service.create(token = token(uid = "host"), input = eventInput()).shouldBeRight().club shouldBe null
    }

    @Test
    fun `an administrator files an event under any club (#789)`() {
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val theirs = club("Downtown TC", owner)

        service
            .create(token = token(uid = "admin"), input = eventInput(clubId = theirs.id))
            .shouldBeRight()
            .club!!
            .id shouldBe theirs.id.toString()
    }

    @Test
    fun `an unknown club still reads as a validation error rather than a forbidden (#789)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        service
            .create(token = token(uid = "host"), input = eventInput(clubId = UUID.randomUUID()))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `re-filing an event under a club you do not own is refused (#789)`() {
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val theirs = club("Downtown TC", owner)
        val ours = club("West End", host)
        val event = eventBy(creatorUid = "host", clubId = null)

        service
            .setClub(token = token(uid = "host"), id = event.id, clubId = theirs.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service.setClub(token = token(uid = "host"), id = event.id, clubId = ours.id).shouldBeRight()
    }

    // --- the widening: a co-owner picks up a colleague's event ------------

    @Test
    fun `a club owner manages their club's event created by someone else, per operation (#789)`() {
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val colleague = provision(uid = "colleague", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1", rated = true)
        val p2 = provision(uid = "p2", rated = true)
        val owned = club("Downtown TC", owner, colleague)
        // Filed by the colleague, so before #789 the owner had no path to any of these operations.
        val event = eventBy(creatorUid = "colleague", clubId = owned.id, participants = listOf(element = p1.id))

        service.get(token = token(uid = "owner"), id = event.id).shouldBeRight()
        service.manageByCode(token = token(uid = "owner"), code = event.publicCode).shouldBeRight()
        service.rename(token = token(uid = "owner"), id = event.id, name = "Autumn Open").shouldBeRight().name shouldBe "Autumn Open"
        service.addParticipant(token = token(uid = "owner"), eventId = event.id, userId = p2.id).shouldBeRight()
        service.decideParticipant(token = token(uid = "owner"), eventId = event.id, userId = p2.id, statusRaw = "HOLD").shouldBeRight()
        service.removeParticipant(token = token(uid = "owner"), eventId = event.id, userId = p2.id).shouldBeRight()
        teamService.list(token = token(uid = "owner"), eventId = event.id).shouldBeRight()
        service.rosterForSeeding(token = token(uid = "owner"), id = event.id).shouldBeRight()
        service.finalize(token = token(uid = "owner"), id = event.id).shouldBeRight().isFinalized.shouldBeTrue()
        service.unfinalize(token = token(uid = "owner"), id = event.id).shouldBeRight().isFinalized.shouldBeFalse()
        service.delete(token = token(uid = "owner"), id = event.id).shouldBeRight()
    }

    @Test
    fun `a plain HOST who owns the club has the same reach as a CLUB_OWNER (#789)`() {
        val hostOwner = provision(uid = "hostowner", roles = setOf(Capability.PLAYER, Capability.HOST))
        val colleague = provision(uid = "colleague", roles = setOf(Capability.PLAYER, Capability.HOST))
        val owned = club("Downtown TC", hostOwner, colleague)
        val event = eventBy(creatorUid = "colleague", clubId = owned.id)

        service.get(token = token(uid = "hostowner"), id = event.id).shouldBeRight()
        service.rename(token = token(uid = "hostowner"), id = event.id, name = "Autumn Open").shouldBeRight()
        service.finalize(token = token(uid = "hostowner"), id = event.id).shouldBeRight().isFinalized.shouldBeTrue()
    }

    // --- refusals for a club the caller does not own ---------------------

    @Test
    fun `a staff caller is refused every event operation for a club they do not own (#789)`() {
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        provision(uid = "outsider", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val p1 = provision(uid = "p1", rated = true)
        val owned = club("Downtown TC", owner)
        val event = eventBy(creatorUid = "owner", clubId = owned.id, participants = listOf(element = p1.id))
        val outsiderToken = token(uid = "outsider")

        service.get(token = outsiderToken, id = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .manageByCode(token = outsiderToken, code = event.publicCode)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .rename(token = outsiderToken, id = event.id, name = "Hijacked")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .setClub(token = outsiderToken, id = event.id, clubId = null)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .addParticipant(token = outsiderToken, eventId = event.id, userId = provision(uid = "p2").id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .removeParticipant(token = outsiderToken, eventId = event.id, userId = p1.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .decideParticipant(token = outsiderToken, eventId = event.id, userId = p1.id, statusRaw = "HOLD")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        teamService.list(token = outsiderToken, eventId = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        teamService
            .create(token = outsiderToken, eventId = event.id, memberUserIds = listOf(element = p1.id), name = null)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service.rosterForSeeding(token = outsiderToken, id = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.finalize(token = outsiderToken, id = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.delete(token = outsiderToken, id = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()

        // Finalized by its rightful owner, un-finalize is refused for the outsider too.
        service.finalize(token = token(uid = "owner"), id = event.id).shouldBeRight()
        service.unfinalize(token = outsiderToken, id = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `an administrator performs every event operation across clubs they do not own (#789)`() {
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provision(uid = "p1", rated = true)
        val p2 = provision(uid = "p2", rated = true)
        val owned = club("Downtown TC", owner)
        val event = eventBy(creatorUid = "owner", clubId = owned.id, participants = listOf(element = p1.id))
        val adminToken = token(uid = "admin")

        service.get(token = adminToken, id = event.id).shouldBeRight()
        service.rename(token = adminToken, id = event.id, name = "Autumn Open").shouldBeRight()
        service.addParticipant(token = adminToken, eventId = event.id, userId = p2.id).shouldBeRight()
        service.removeParticipant(token = adminToken, eventId = event.id, userId = p2.id).shouldBeRight()
        teamService.list(token = adminToken, eventId = event.id).shouldBeRight()
        service.rosterForSeeding(token = adminToken, id = event.id).shouldBeRight()
        service.setClub(token = adminToken, id = event.id, clubId = null).shouldBeRight()
        service.finalize(token = adminToken, id = event.id).shouldBeRight()
        service.unfinalize(token = adminToken, id = event.id).shouldBeRight()
        service.delete(token = adminToken, id = event.id).shouldBeRight()
    }

    // --- clubless events keep the creator fallback -----------------------

    @Test
    fun `a clubless event stays manageable by its creator and refused for another staff caller (#789)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "other", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val event = eventBy(creatorUid = "host", clubId = null)

        service.get(token = token(uid = "host"), id = event.id).shouldBeRight()
        service.rename(token = token(uid = "host"), id = event.id, name = "Autumn Open").shouldBeRight()
        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight().isFinalized.shouldBeTrue()
        service.unfinalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        // Holding CLUB_OWNER is not itself a claim on someone else's clubless event.
        service.get(token = token(uid = "other"), id = event.id).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .finalize(token = token(uid = "other"), id = event.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `an event filed under a club the creator no longer owns stays theirs, grandfathered (#789)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val owned = club("Downtown TC", host)
        val event = eventBy(creatorUid = "host", clubId = owned.id)

        // The host is dropped as an owner — exactly the pre-#789 data shape the grandfather clause exists
        // for, so no migration is needed and nobody loses access to an event they filed.
        clubs.removeOwner(clubId = owned.id, userId = host.id)

        service.get(token = token(uid = "host"), id = event.id).shouldBeRight()
        service.rename(token = token(uid = "host"), id = event.id, name = "Autumn Open").shouldBeRight()
    }

    // --- list scoping -----------------------------------------------------

    @Test
    fun `the event list returns owned-club events plus own-created ones, and nothing else (#789)`() {
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val colleague = provision(uid = "colleague", roles = setOf(Capability.PLAYER, Capability.HOST))
        val outsider = provision(uid = "outsider", roles = setOf(Capability.PLAYER, Capability.HOST))
        val owned = club("Downtown TC", owner, colleague)
        val theirs = club("Northside", outsider)

        // Their club's event, filed by a colleague — the whole point of the widening.
        val colleagues = eventBy(creatorUid = "colleague", clubId = owned.id, name = "Colleague's")
        // Their own clubless event, reachable only through the creator clause.
        val mine = eventBy(creatorUid = "owner", clubId = null, name = "Mine")
        // Someone else's club, someone else's event — invisible.
        val foreign = eventBy(creatorUid = "outsider", clubId = theirs.id, name = "Foreign")

        service
            .list(token = token(uid = "owner"))
            .shouldBeRight()
            .map { it.name }
            .shouldContainExactlyInAnyOrder(colleagues.name, mine.name)

        // An administrator still sees everything.
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        service
            .list(token = token(uid = "admin"))
            .shouldBeRight()
            .map { it.name }
            .shouldContainExactlyInAnyOrder(colleagues.name, mine.name, foreign.name)
    }

    @Test
    fun `the event list is empty for a staff caller who owns no club and created nothing (#789)`() {
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        provision(uid = "newcomer", roles = setOf(Capability.PLAYER, Capability.HOST))
        eventBy(creatorUid = "owner", clubId = club("Downtown TC", owner).id)

        service.list(token = token(uid = "newcomer")).shouldBeRight() shouldBe emptyList()
    }

    // --- matches inherit the event's club rule ---------------------------

    @Test
    fun `match operations under an event inherit its club rule (#789)`() {
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val outsider = provision(uid = "outsider", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val p1 = provision(uid = "p1", rated = true)
        val p2 = provision(uid = "p2", rated = true)
        val owned = club("Downtown TC", owner)
        val event = eventBy(creatorUid = "owner", clubId = owned.id, participants = listOf(p1.id, p2.id))
        val match = fixture(event = event, creator = owner, p1 = p1, p2 = p2)
        val outsiderToken = token(uid = "outsider")

        matchService
            .setHandicaps(token = outsiderToken, matchId = match.id, team1Handicap = BigDecimal("0.9"), team2Handicap = null)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        matchService
            .setActive(token = outsiderToken, matchId = match.id, active = false)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        matchService
            .reorder(token = outsiderToken, matchIds = listOf(element = match.id))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        matchService
            .uploadResult(token = outsiderToken, matchId = match.id, request = straightSets())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()

        // The club's owner may run all of them.
        val ownerToken = token(uid = "owner")
        matchService
            .setHandicaps(token = ownerToken, matchId = match.id, team1Handicap = BigDecimal("0.9"), team2Handicap = null)
            .shouldBeRight()
        matchService.reorder(token = ownerToken, matchIds = listOf(element = match.id)).shouldBeRight()
        matchService.uploadResult(token = ownerToken, matchId = match.id, request = straightSets()).shouldBeRight()
        matchService.setActive(token = ownerToken, matchId = match.id, active = false).shouldBeRight()
    }

    @Test
    fun `a co-owner may run the matches of their club's event created by someone else (#789)`() {
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val colleague = provision(uid = "colleague", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1", rated = true)
        val p2 = provision(uid = "p2", rated = true)
        val owned = club("Downtown TC", owner, colleague)
        val event = eventBy(creatorUid = "colleague", clubId = owned.id, participants = listOf(p1.id, p2.id))
        val match = fixture(event = event, creator = colleague, p1 = p1, p2 = p2)

        matchService.uploadResult(token = token(uid = "owner"), matchId = match.id, request = straightSets()).shouldBeRight()
    }

    @Test
    fun `an outsider cannot create a fixture under a club's event, its owner can (#789)`() {
        val owner = provision(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        provision(uid = "outsider", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val p1 = provision(uid = "p1", rated = true)
        val p2 = provision(uid = "p2", rated = true)
        val owned = club("Downtown TC", owner)
        val event = eventBy(creatorUid = "owner", clubId = owned.id, participants = listOf(p1.id, p2.id))
        val request =
            FixtureInput(
                matchFormat = TeamType.SINGLES,
                matchType = MatchType.OPEN_PLAY,
                matchDate = LocalDate.now(),
                team1 = listOf(element = p1.id),
                team2 = listOf(element = p2.id),
                eventId = event.id,
            )

        matchService
            .createFixture(token = token(uid = "outsider"), request = request)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        matchService.createFixture(token = token(uid = "owner"), request = request).shouldBeRight()
    }

    @Test
    fun `a match with no event keeps the plain staff gate (#789)`() {
        provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "other", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provision(uid = "p1", rated = true)
        val p2 = provision(uid = "p2", rated = true)
        val request =
            FixtureInput(
                matchFormat = TeamType.SINGLES,
                matchType = MatchType.OPEN_PLAY,
                matchDate = LocalDate.now(),
                team1 = listOf(element = p1.id),
                team2 = listOf(element = p2.id),
            )
        val match = matchService.createFixture(token = token(uid = "host"), request = request).shouldBeRight()

        // There is no event, so there is nothing to anchor ownership on — any staff caller may record it.
        matchService
            .uploadResult(token = token(uid = "other"), matchId = UUID.fromString(match.id), request = straightSets())
            .shouldBeRight()
    }
}
