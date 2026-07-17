// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.CreateClubCommand
import org.skopeo.model.CreateEventCommand
import org.skopeo.model.CreateFixtureCommand
import org.skopeo.model.EventType
import org.skopeo.model.MatchType
import org.skopeo.model.NameType
import org.skopeo.model.PointsPolicy
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.TeamType
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.testsupport.PostgresTestDatabase
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class PointsBudgetRepositoryTest {
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
    private val matches = MatchRepository()
    private val repo = PointsBudgetRepository()

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
                    capabilities = emptySet(),
                ),
        ).id

    @Test
    fun `listPolicies returns the seeded policy per event type (#403)`() {
        val policies = repo.listPolicies()
        policies shouldHaveSize EventType.entries.size
        policies.map { it.eventType }.toSet() shouldBe EventType.entries.toSet()
    }

    @Test
    fun `findPolicy reads one type and upsertPolicy overwrites it in place (#403)`() {
        repo.findPolicy(eventType = EventType.LEAGUE).shouldNotBeNull().minPoints shouldBe 5

        val updated =
            repo.upsertPolicy(
                policy = PointsPolicy(eventType = EventType.LEAGUE, minPoints = 7, maxPoints = 99, maxValidityDays = 120),
            )
        updated.minPoints shouldBe 7
        updated.maxPoints shouldBe 99
        updated.maxValidityDays shouldBe 120

        repo.findPolicy(eventType = EventType.LEAGUE).shouldNotBeNull().maxPoints shouldBe 99
        // Upsert is in place — still one row per type.
        repo.listPolicies() shouldHaveSize EventType.entries.size
    }

    @Test
    fun `upsertBudget inserts then updates a club × type row, keyed by the pair (#403)`() {
        val admin = newUser(uid = "admin")
        val club = clubs.create(command = CreateClubCommand(name = "Club", createdBy = admin)).id

        repo.listBudgetsForClub(clubId = club) shouldHaveSize 0

        val inserted =
            repo.upsertBudget(clubId = club, eventType = EventType.OPEN_PLAY, budgetedPoints = 100, updatedBy = admin)
        inserted.budgetedPoints shouldBe 100
        repo.listBudgetsForClub(clubId = club) shouldHaveSize 1

        // Same (club, type) updates in place.
        val updated =
            repo.upsertBudget(clubId = club, eventType = EventType.OPEN_PLAY, budgetedPoints = 250, updatedBy = admin)
        updated.budgetedPoints shouldBe 250
        repo.listBudgetsForClub(clubId = club) shouldHaveSize 1

        // A different type is a separate row under the same PK space.
        repo.upsertBudget(clubId = club, eventType = EventType.TOURNAMENT, budgetedPoints = 500, updatedBy = admin)
        repo.listBudgetsForClub(clubId = club) shouldHaveSize 2
    }

    @Test
    fun `listBudgets spans all clubs (#403)`() {
        val admin = newUser(uid = "admin")
        val clubA = clubs.create(command = CreateClubCommand(name = "A", createdBy = admin)).id
        val clubB = clubs.create(command = CreateClubCommand(name = "B", createdBy = admin)).id
        repo.upsertBudget(clubId = clubA, eventType = EventType.LEAGUE, budgetedPoints = 10, updatedBy = admin)
        repo.upsertBudget(clubId = clubB, eventType = EventType.LEAGUE, budgetedPoints = 20, updatedBy = admin)

        repo.listBudgets() shouldHaveSize 2
    }

    @Test
    fun `upsertPolicy inserts a policy for a type that has no row (#403)`() {
        // The seed gives every type a row; delete one so upsert takes the insert (not update) branch.
        org.jetbrains.exposed.sql.transactions.transaction {
            exec(stmt = "DELETE FROM points_policies WHERE event_type = 'TOURNAMENT'")
        }
        repo.findPolicy(eventType = EventType.TOURNAMENT).shouldBeNull()

        val inserted =
            repo.upsertPolicy(
                policy = PointsPolicy(eventType = EventType.TOURNAMENT, minPoints = 2, maxPoints = 20, maxValidityDays = 200),
            )
        inserted.minPoints shouldBe 2
        repo.findPolicy(eventType = EventType.TOURNAMENT).shouldNotBeNull().maxValidityDays shouldBe 200
    }

    @Test
    fun `findPolicy is null for an event type with no policy row (#403)`() {
        // Truncate wipes and reseeds all three types; delete one to hit the null-return path.
        org.jetbrains.exposed.sql.transactions.transaction {
            exec(stmt = "DELETE FROM points_policies WHERE event_type = 'TOURNAMENT'")
        }
        repo.findPolicy(eventType = EventType.TOURNAMENT).shouldBeNull()
    }

    // --- Emergent reservation (#403 Phase C): sumReservedPoints, no reservation table. ---

    private fun leagueEvent(
        clubId: UUID?,
        createdBy: UUID,
        participants: List<UUID>,
        finalized: Boolean = false,
    ): UUID {
        val id =
            events.create(
                command =
                    CreateEventCommand(
                        name = "League",
                        startDate = LocalDate.now(),
                        endDate = LocalDate.now().plusDays(7),
                        participantIds = participants,
                        createdBy = createdBy,
                        clubId = clubId,
                        type = EventType.LEAGUE,
                        minPointsPerMatch = 5,
                        maxPointsPerMatch = 50,
                        pointValidityStart = LocalDate.now(),
                        pointValidityEnd = LocalDate.now().plusDays(30),
                    ),
            ).id
        if (finalized) events.finalize(id = id, finalizedAt = LocalDateTime.now(), finalizedBy = createdBy)
        return id
    }

    private fun fixture(
        eventId: UUID,
        createdBy: UUID,
        players: List<UUID>,
        designated: Int?,
        format: TeamType = TeamType.SINGLES,
    ) = matches.createFixture(
        command =
            CreateFixtureCommand(
                matchFormat = format,
                matchType = MatchType.OPEN_PLAY,
                matchDate = LocalDate.now(),
                team1UserIds = listOf(element = players[0]),
                team2UserIds = listOf(element = players[1]),
                team1Name = "t1",
                team2Name = "t2",
                createdBy = createdBy,
                eventId = eventId,
                designatedPoints = designated,
            ),
    )

    @Test
    fun `sumReservedPoints sums active non-finalized designations times team size (#403)`() {
        val admin = newUser(uid = "admin")
        val p1 = newUser(uid = "p1")
        val p2 = newUser(uid = "p2")
        val club = clubs.create(command = CreateClubCommand(name = "Club", createdBy = admin)).id
        val event = leagueEvent(clubId = club, createdBy = admin, participants = listOf(p1, p2))

        repo.sumReservedPoints(clubId = club, eventType = EventType.LEAGUE) shouldBe 0

        // Two singles designations of 30 and 10; team size 1 → 40 total.
        fixture(eventId = event, createdBy = admin, players = listOf(p1, p2), designated = 30)
        fixture(eventId = event, createdBy = admin, players = listOf(p1, p2), designated = 10)
        // A fixture with no designation contributes nothing.
        fixture(eventId = event, createdBy = admin, players = listOf(p1, p2), designated = null)
        repo.sumReservedPoints(clubId = club, eventType = EventType.LEAGUE) shouldBe 40
    }

    @Test
    fun `sumReservedPoints counts a doubles fixture at designation times two (#403)`() {
        val admin = newUser(uid = "admin")
        val a = newUser(uid = "a")
        val b = newUser(uid = "b")
        val club = clubs.create(command = CreateClubCommand(name = "Club", createdBy = admin)).id
        val event = leagueEvent(clubId = club, createdBy = admin, participants = listOf(a, b))

        matches.createFixture(
            command =
                CreateFixtureCommand(
                    matchFormat = TeamType.DOUBLES,
                    matchType = MatchType.OPEN_PLAY,
                    matchDate = LocalDate.now(),
                    team1UserIds = listOf(a, b),
                    team2UserIds = listOf(a, b),
                    team1Name = "t1",
                    team2Name = "t2",
                    createdBy = admin,
                    eventId = event,
                    designatedPoints = 20,
                ),
        )
        // team size = 2 → 20 × 2 = 40.
        repo.sumReservedPoints(clubId = club, eventType = EventType.LEAGUE) shouldBe 40
    }

    @Test
    fun `sumReservedPoints excludes finalized events and voided fixtures (#403)`() {
        val admin = newUser(uid = "admin")
        val p1 = newUser(uid = "p1")
        val p2 = newUser(uid = "p2")
        val club = clubs.create(command = CreateClubCommand(name = "Club", createdBy = admin)).id

        // A finalized event's fixtures fall out (they become ActiveAwarded in Phase D).
        val finalized = leagueEvent(clubId = club, createdBy = admin, participants = listOf(p1, p2), finalized = true)
        fixture(eventId = finalized, createdBy = admin, players = listOf(p1, p2), designated = 30)
        repo.sumReservedPoints(clubId = club, eventType = EventType.LEAGUE) shouldBe 0

        // An open event: a designated fixture reserves, then voiding it releases the reservation.
        val open = leagueEvent(clubId = club, createdBy = admin, participants = listOf(p1, p2))
        val match = fixture(eventId = open, createdBy = admin, players = listOf(p1, p2), designated = 25)
        repo.sumReservedPoints(clubId = club, eventType = EventType.LEAGUE) shouldBe 25
        matches.setActive(matchId = match.id, active = false, disabledAt = LocalDateTime.now())
        repo.sumReservedPoints(clubId = club, eventType = EventType.LEAGUE) shouldBe 0
    }

    // --- Active-award accounting (#403 Phase D): sumActiveAwards, the awarded side of reserve→award. ---

    private fun awardFor(
        userId: UUID,
        eventId: UUID?,
        points: String,
        status: org.skopeo.model.AwardStatus = org.skopeo.model.AwardStatus.ACTIVE,
        validFrom: LocalDateTime = LocalDateTime.now().minusDays(1),
        validUntil: LocalDateTime = LocalDateTime.now().plusDays(30),
    ) = RankingPointRepository().award(
        write =
            org.skopeo.model.RankingPointAwardWrite(
                userId = userId,
                points = java.math.BigDecimal(points),
                pointClass = org.skopeo.model.PointClass.SEASONAL_TOURNAMENT_1M,
                sourceType = org.skopeo.model.PointSourceType.INTERNAL,
                sourceId = eventId?.toString(),
                band = "4.0",
                sex = "Male",
                reason = null,
                validFrom = validFrom,
                validUntil = validUntil,
                status = status,
                revokesAwardId = null,
                grantedBy = null,
                awardedAt = LocalDateTime.now(),
                eventId = eventId,
            ),
    )

    @Test
    fun `sumActiveAwards sums active in-window awards for the club and type, excluding expired revoked and unlinked (#403)`() {
        val admin = newUser(uid = "admin")
        val p1 = newUser(uid = "p1")
        val club = clubs.create(command = CreateClubCommand(name = "Club", createdBy = admin)).id
        val event = leagueEvent(clubId = club, createdBy = admin, participants = listOf(element = p1), finalized = true)

        repo.sumActiveAwards(clubId = club, eventType = EventType.LEAGUE) shouldBe 0

        awardFor(userId = p1, eventId = event, points = "30")
        awardFor(userId = p1, eventId = event, points = "20")
        // Expired (window ended yesterday) → excluded.
        awardFor(
            userId = p1,
            eventId = event,
            points = "999",
            validFrom = LocalDateTime.now().minusDays(10),
            validUntil = LocalDateTime.now().minusDays(1),
        )
        // Revoked → excluded.
        awardFor(userId = p1, eventId = event, points = "999", status = org.skopeo.model.AwardStatus.REVOKED)
        // Unlinked (manual grant, no event) → excluded (not joined to any event).
        awardFor(userId = p1, eventId = null, points = "999")

        repo.sumActiveAwards(clubId = club, eventType = EventType.LEAGUE) shouldBe 50
        // A different type sees none of the LEAGUE awards.
        repo.sumActiveAwards(clubId = club, eventType = EventType.TOURNAMENT) shouldBe 0
    }

    @Test
    fun `reserved and awarded are mutually exclusive across finalize, keeping the sum continuous (#403)`() {
        val admin = newUser(uid = "admin")
        val p1 = newUser(uid = "p1")
        val p2 = newUser(uid = "p2")
        val club = clubs.create(command = CreateClubCommand(name = "Club", createdBy = admin)).id
        // An OPEN (non-finalized) LEAGUE event with a 40-point singles fixture: shows as reserved, not awarded.
        val event = leagueEvent(clubId = club, createdBy = admin, participants = listOf(p1, p2))
        fixture(eventId = event, createdBy = admin, players = listOf(p1, p2), designated = 40)
        repo.sumReservedPoints(clubId = club, eventType = EventType.LEAGUE) shouldBe 40
        repo.sumActiveAwards(clubId = club, eventType = EventType.LEAGUE) shouldBe 0

        // Finalize the event and record the award (what EventFinalizeAwarder does): the fixture leaves
        // Reserved (finalized_at set) and the same points reappear as an active award → total unchanged.
        events.finalize(id = event, finalizedAt = LocalDateTime.now(), finalizedBy = admin)
        awardFor(userId = p1, eventId = event, points = "40")
        repo.sumReservedPoints(clubId = club, eventType = EventType.LEAGUE) shouldBe 0
        repo.sumActiveAwards(clubId = club, eventType = EventType.LEAGUE) shouldBe 40
    }

    // --- Per-event points (#403 Phase E): sumDesignatedPointsForEvent, sumAwardedPointsForEvent. ---

    @Test
    fun `sumDesignatedPointsForEvent sums the event's designations times team size (#403)`() {
        val admin = newUser(uid = "admin")
        val p1 = newUser(uid = "p1")
        val p2 = newUser(uid = "p2")
        val club = clubs.create(command = CreateClubCommand(name = "Club", createdBy = admin)).id
        val event = leagueEvent(clubId = club, createdBy = admin, participants = listOf(p1, p2))

        repo.sumDesignatedPointsForEvent(eventId = event) shouldBe 0

        // Singles designations of 30 and 10 (team size 1) = 40; a null-designation fixture adds nothing.
        fixture(eventId = event, createdBy = admin, players = listOf(p1, p2), designated = 30)
        fixture(eventId = event, createdBy = admin, players = listOf(p1, p2), designated = 10)
        fixture(eventId = event, createdBy = admin, players = listOf(p1, p2), designated = null)
        repo.sumDesignatedPointsForEvent(eventId = event) shouldBe 40
    }

    @Test
    fun `sumDesignatedPointsForEvent counts a doubles fixture at designation times two (#403)`() {
        val admin = newUser(uid = "admin")
        val a = newUser(uid = "a")
        val b = newUser(uid = "b")
        val club = clubs.create(command = CreateClubCommand(name = "Club", createdBy = admin)).id
        val event = leagueEvent(clubId = club, createdBy = admin, participants = listOf(a, b))

        matches.createFixture(
            command =
                CreateFixtureCommand(
                    matchFormat = TeamType.DOUBLES,
                    matchType = MatchType.OPEN_PLAY,
                    matchDate = LocalDate.now(),
                    team1UserIds = listOf(a, b),
                    team2UserIds = listOf(a, b),
                    team1Name = "t1",
                    team2Name = "t2",
                    createdBy = admin,
                    eventId = event,
                    designatedPoints = 20,
                ),
        )
        repo.sumDesignatedPointsForEvent(eventId = event) shouldBe 40
    }

    @Test
    fun `sumDesignatedPointsForEvent counts a finalized event but excludes cancelled and voided fixtures (#403)`() {
        val admin = newUser(uid = "admin")
        val p1 = newUser(uid = "p1")
        val p2 = newUser(uid = "p2")
        val club = clubs.create(command = CreateClubCommand(name = "Club", createdBy = admin)).id

        // Designation is the planned total regardless of finalize — a finalized event still counts.
        val finalized = leagueEvent(clubId = club, createdBy = admin, participants = listOf(p1, p2), finalized = true)
        fixture(eventId = finalized, createdBy = admin, players = listOf(p1, p2), designated = 30)
        repo.sumDesignatedPointsForEvent(eventId = finalized) shouldBe 30

        // Voided (inactive) and cancelled fixtures drop out.
        val open = leagueEvent(clubId = club, createdBy = admin, participants = listOf(p1, p2))
        val voided = fixture(eventId = open, createdBy = admin, players = listOf(p1, p2), designated = 25)
        val cancelled = fixture(eventId = open, createdBy = admin, players = listOf(p1, p2), designated = 15)
        repo.sumDesignatedPointsForEvent(eventId = open) shouldBe 40
        matches.setActive(matchId = voided.id, active = false, disabledAt = LocalDateTime.now())
        org.jetbrains.exposed.sql.transactions.transaction {
            exec(stmt = "UPDATE matches SET status = 'CANCELLED' WHERE id = '${cancelled.id}'")
        }
        repo.sumDesignatedPointsForEvent(eventId = open) shouldBe 0
    }

    @Test
    fun `sumAwardedPointsForEvent sums active in-window awards for the event, excluding expired revoked and other events (#403)`() {
        val admin = newUser(uid = "admin")
        val p1 = newUser(uid = "p1")
        val club = clubs.create(command = CreateClubCommand(name = "Club", createdBy = admin)).id
        val event = leagueEvent(clubId = club, createdBy = admin, participants = listOf(element = p1), finalized = true)
        val other = leagueEvent(clubId = club, createdBy = admin, participants = listOf(element = p1), finalized = true)

        repo.sumAwardedPointsForEvent(eventId = event) shouldBe 0

        awardFor(userId = p1, eventId = event, points = "30")
        awardFor(userId = p1, eventId = event, points = "20")
        // Expired (window ended yesterday) → excluded.
        awardFor(
            userId = p1,
            eventId = event,
            points = "999",
            validFrom = LocalDateTime.now().minusDays(10),
            validUntil = LocalDateTime.now().minusDays(1),
        )
        // Revoked → excluded.
        awardFor(userId = p1, eventId = event, points = "999", status = org.skopeo.model.AwardStatus.REVOKED)
        // A different event's award → excluded from this event's sum.
        awardFor(userId = p1, eventId = other, points = "999")

        repo.sumAwardedPointsForEvent(eventId = event) shouldBe 50
    }

    @Test
    fun `findBudget returns the budgeted points or zero when no row exists (#403)`() {
        val admin = newUser(uid = "admin")
        val club = clubs.create(command = CreateClubCommand(name = "Club", createdBy = admin)).id
        repo.findBudget(clubId = club, eventType = EventType.LEAGUE) shouldBe 0
        repo.upsertBudget(clubId = club, eventType = EventType.LEAGUE, budgetedPoints = 120, updatedBy = admin)
        repo.findBudget(clubId = club, eventType = EventType.LEAGUE) shouldBe 120
    }

    @Test
    fun `deleting a club cascades away its budgets (#403)`() {
        val admin = newUser(uid = "admin")
        val club = clubs.create(command = CreateClubCommand(name = "Doomed", createdBy = admin)).id
        repo.upsertBudget(clubId = club, eventType = EventType.OPEN_PLAY, budgetedPoints = 10, updatedBy = admin)

        org.jetbrains.exposed.sql.transactions.transaction {
            exec(stmt = "DELETE FROM clubs WHERE id = '$club'")
        }
        repo.listBudgetsForClub(clubId = club) shouldHaveSize 0
    }
}
