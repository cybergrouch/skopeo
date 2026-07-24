// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.event

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuthProvider
import org.skopeo.model.AwardStatus
import org.skopeo.model.Capability
import org.skopeo.model.CreateFixtureCommand
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
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.rating.RatingCalculationService
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Reverse Ratings (#478): reversing an already-rated event so an erroneous score can be corrected and the
 * event re-finalized. Sets up realistic rated state through the actual calc-commit path, then asserts the
 * reversal restores pre-event ratings exactly, soft-deletes history, revokes awards, re-queues matches,
 * and clears the finalize flag — plus the tip guard and authz.
 */
class EventReverseRatingsTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val events = EventRepository()
    private val matchRepo = MatchRepository()
    private val ratings = RatingRepository()
    private val awardRepo = RankingPointRepository()
    private val service = EventService(events = events, users = users)
    private val calc = RatingCalculationService()

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

    private fun rate(
        userId: UUID,
        level: String,
    ) = ratings.setRating(userId = userId, rating = BigDecimal(level), level = level)

    private fun budgetedEvent(
        hostUid: String,
        participants: List<UUID>,
    ) = service.create(
        token = token(uid = hostUid),
        input =
            CreateEventInput(
                name = "Spring Open",
                startDate = LocalDate.now(),
                endDate = LocalDate.now().plusDays(7),
                participantIds = participants,
                // TOURNAMENT is the host-designated awarding type (#525) — LEAGUE no longer awards.
                type = EventType.TOURNAMENT,
                minPointsPerMatch = 10,
                maxPointsPerMatch = 50,
                pointValidityStart = LocalDate.now(),
                pointValidityEnd = LocalDate.now().plusDays(30),
            ),
    ).shouldBeRight().event

    /** Seed a COMPLETED singles fixture on [date] where team1 (p1) beats p2, designating [designated] points. */
    private fun seedCompletedFixture(
        eventId: UUID,
        host: User,
        p1: User,
        p2: User,
        designated: Int? = 30,
        date: LocalDate = LocalDate.now(),
    ): Match {
        val match =
            matchRepo.createFixture(
                command =
                    CreateFixtureCommand(
                        matchFormat = TeamType.SINGLES,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = date,
                        team1UserIds = listOf(element = p1.id),
                        team2UserIds = listOf(element = p2.id),
                        team1Name = "t1",
                        team2Name = "t2",
                        createdBy = host.id,
                        eventId = eventId,
                        designatedPoints = designated,
                    ),
            )
        matchRepo.addResult(
            matchId = match.id,
            sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 4, winnerTeamId = match.team1.teamId)),
            winnerTeamId = match.team1.teamId,
            recordedBy = host.id,
            completedAt = java.time.LocalDateTime.now(),
        )
        return matchRepo.findById(matchId = match.id).shouldBeRight()
    }

    /** Finalize [eventId] (host) then commit the rating calculation (admin), leaving the event's matches rated. */
    private fun finalizeAndRate(
        eventId: UUID,
        hostUid: String,
        adminUid: String,
    ) {
        service.finalize(token = token(uid = hostUid), id = eventId).shouldBeRight()
        calc.calculate(token = token(uid = adminUid), dryRun = false).shouldBeRight()
    }

    /** A fully-set-up, rated, at-tip LEAGUE event with two players, ready to reverse. */
    private data class RatedEvent(
        val eventId: UUID,
        val host: User,
        val admin: User,
        val p1: User,
        val p2: User,
        val match: Match,
    )

    private fun ratedEvent(): RatedEvent {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        val match = seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2)
        finalizeAndRate(eventId = event.id, hostUid = "host", adminUid = "admin")
        return RatedEvent(eventId = event.id, host = host, admin = admin, p1 = p1, p2 = p2, match = match)
    }

    @Test
    fun `reversing an at-tip rated event restores each participant exactly to their pre-event rating (#478)`() {
        val re = ratedEvent()
        // After the calc commit p1's rating moved off 4.0 (a decided match always shifts both sides).
        val p1After = ratings.findCurrentRating(userId = re.p1.id).shouldNotBeNull()
        (p1After.currentRating == BigDecimal("4.000000")).shouldBeFalse()

        service.reverseRatings(token = token(uid = "admin"), id = re.eventId).shouldBeRight()

        // Both are restored to exactly their pre-event 4.0 (the previous_rating stored on the history rows).
        ratings.findCurrentRating(userId = re.p1.id).shouldNotBeNull().currentRating shouldBe BigDecimal("4.000000")
        ratings.findCurrentRating(userId = re.p2.id).shouldNotBeNull().currentRating shouldBe BigDecimal("4.000000")
    }

    @Test
    fun `reversal soft-deletes the event's history rows and excludes them from the read (#478)`() {
        val re = ratedEvent()
        ratings.historyForMatches(matchIds = listOf(element = re.match.id)) shouldHaveSize 2
        ratings.historyByUser(userId = re.p1.id).shouldHaveSize(size = 1)

        service.reverseRatings(token = token(uid = "admin"), id = re.eventId).shouldBeRight()

        // The rows are superseded, not hard-deleted: the read paths exclude them (empty), the rows remain.
        ratings.historyForMatches(matchIds = listOf(element = re.match.id)).shouldBeEmpty()
        ratings.historyByUser(userId = re.p1.id).shouldBeEmpty()
    }

    @Test
    fun `reversal revokes the event's active awards, appending a revocation marker (#478)`() {
        val re = ratedEvent()
        awardRepo.listActiveByEvent(eventId = re.eventId) shouldHaveSize 1

        service.reverseRatings(token = token(uid = "admin"), id = re.eventId).shouldBeRight()

        awardRepo.listActiveByEvent(eventId = re.eventId).shouldBeEmpty()
        val rows = awardRepo.listByUser(userId = re.p1.id)
        rows shouldHaveSize 2
        rows.count { it.status == AwardStatus.ACTIVE } shouldBe 0
        val marker = rows.single { it.revokesAwardId != null }
        marker.reason.shouldNotBeNull() shouldContain "Reversed on rating reversal"
    }

    @Test
    fun `reversal clears rated_at on the event's matches and clears the finalize flag (#478)`() {
        val re = ratedEvent()
        matchRepo.findById(matchId = re.match.id).shouldBeRight().ratedAt.shouldNotBeNull()

        val view = service.reverseRatings(token = token(uid = "admin"), id = re.eventId).shouldBeRight()

        view.event.isFinalized.shouldBeFalse()
        val persisted = events.findById(id = re.eventId).shouldNotBeNull()
        persisted.finalizedAt.shouldBeNull()
        persisted.finalizedBy.shouldBeNull()
        matchRepo.findById(matchId = re.match.id).shouldBeRight().ratedAt.shouldBeNull()
    }

    @Test
    fun `reversal writes an EVENT_RATINGS_REVERSED audit entry (#478)`() {
        val re = ratedEvent()

        service.reverseRatings(token = token(uid = "admin"), id = re.eventId).shouldBeRight()

        AuditRepository().list(actions = listOf(element = AuditAction.EVENT_RATINGS_REVERSED), limit = 10, offset = 0)
            .first.single().let {
                it.actorUserId shouldBe re.admin.id
                it.entityId shouldBe re.eventId
                it.entityType shouldBe AuditEntityType.EVENT
                it.summary shouldContain "Reversed ratings for event Spring Open"
                it.details["participantsRestored"] shouldBe "2"
                it.details["awardsRevoked"] shouldBe "1"
            }
    }

    @Test
    fun `after reversal the event re-enters the pending queue and can be re-finalized (#478)`() {
        val re = ratedEvent()

        service.reverseRatings(token = token(uid = "admin"), id = re.eventId).shouldBeRight()

        // The matches re-enter the pending-calculation queue (once re-finalized) and the event re-finalizes.
        service.finalize(token = token(uid = "host"), id = re.eventId).shouldBeRight().event.isFinalized.shouldBeTrue()
        calc.calculate(token = token(uid = "admin"), dryRun = true).shouldBeRight().matches shouldHaveSize 1
    }

    @Test
    fun `reversal is refused when a participant has a later rated match on top (#478)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")

        // Event A (earlier), then event B (later), sharing p1 — B builds on top of A.
        val eventA = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        seedCompletedFixture(eventId = eventA.id, host = host, p1 = p1, p2 = p2, date = LocalDate.now().minusDays(5))
        service.finalize(token = token(uid = "host"), id = eventA.id).shouldBeRight()
        val eventB =
            service.create(
                token = token(uid = "host"),
                input =
                    CreateEventInput(
                        name = "Later Cup",
                        startDate = LocalDate.now(),
                        endDate = LocalDate.now().plusDays(1),
                        participantIds = listOf(p1.id, p2.id),
                        type = EventType.TOURNAMENT,
                        minPointsPerMatch = 10,
                        maxPointsPerMatch = 50,
                        pointValidityStart = LocalDate.now(),
                        pointValidityEnd = LocalDate.now().plusDays(30),
                    ),
            ).shouldBeRight().event
        seedCompletedFixture(eventId = eventB.id, host = host, p1 = p1, p2 = p2, date = LocalDate.now())
        service.finalize(token = token(uid = "host"), id = eventB.id).shouldBeRight()
        // Rate both events together: A then B in one batch, so B is dated on top of A for p1/p2.
        calc.calculate(token = token(uid = "admin"), dryRun = false).shouldBeRight()

        // Reversing the earlier event A is refused — B was rated on top of it.
        val error =
            service.reverseRatings(token = token(uid = "admin"), id = eventA.id)
                .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        error.message shouldContain "later matches have already been rated"
        // Nothing was touched: A stays finalized and its award stays live.
        events.findById(id = eventA.id).shouldNotBeNull().isFinalized.shouldBeTrue()
        awardRepo.listActiveByEvent(eventId = eventA.id) shouldHaveSize 1

        // The tip event B, however, reverses cleanly.
        service.reverseRatings(token = token(uid = "admin"), id = eventB.id).shouldBeRight()
    }

    @Test
    fun `reversal is refused when the event is not finalized, and NotFound when missing (#478)`() {
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val event = budgetedEvent(hostUid = "host", participants = emptyList())

        service.reverseRatings(token = token(uid = "admin"), id = event.id)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
            .message shouldContain "not finalized"
        service.reverseRatings(token = token(uid = "admin"), id = UUID.randomUUID())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
        host.id shouldBe event.createdBy
    }

    @Test
    fun `reversal is refused when a finalized event has no rated matches, advising un-finalize (#478)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        val event = budgetedEvent(hostUid = "host", participants = listOf(p1.id, p2.id))
        seedCompletedFixture(eventId = event.id, host = host, p1 = p1, p2 = p2)
        // Finalize but do NOT run the calc, so no match is rated.
        service.finalize(token = token(uid = "host"), id = event.id).shouldBeRight()

        val error =
            service.reverseRatings(token = token(uid = "admin"), id = event.id)
                .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        error.message shouldContain "no rated matches"
    }

    @Test
    fun `reversal is ADMINISTRATOR-only - a host or club owner is forbidden (#478)`() {
        val re = ratedEvent()
        provision(uid = "clubowner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))

        service.reverseRatings(token = token(uid = "host"), id = re.eventId)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.reverseRatings(token = token(uid = "clubowner"), id = re.eventId)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.reverseRatings(token = token(uid = "ghost"), id = re.eventId)
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        // Nothing was reversed by the forbidden attempts.
        events.findById(id = re.eventId).shouldNotBeNull().isFinalized.shouldBeTrue()
    }
}
