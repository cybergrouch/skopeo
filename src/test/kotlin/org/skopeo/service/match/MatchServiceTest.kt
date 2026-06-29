// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.match

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.dto.match.MatchResultRequest
import org.skopeo.dto.match.SetScoreRequest
import org.skopeo.model.AuditAction
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.CreateEventCommand
import org.skopeo.model.MatchQuery
import org.skopeo.model.MatchStatus
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
import org.skopeo.repository.MatchesTable
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.rating.RatingCalculationService
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class MatchServiceTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val ratings = RatingRepository()
    private val matchRepo = MatchRepository()
    private val service = MatchService(matches = matchRepo, ratings = ratings, users = users)
    private val calc = RatingCalculationService(matches = matchRepo, ratings = ratings, users = users)

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun provisionUser(
        uid: String,
        roles: Set<Capability> = setOf(Capability.PLAYER),
        rated: Boolean = false,
    ): User {
        val user =
            users.provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid,
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                        names = listOf(UserName(type = NameType.DISPLAY, value = uid)),
                        capabilities = roles,
                    ),
            )
        if (rated) {
            ratings.setRating(
                userId = user.id,
                rating = BigDecimal("4.0"),
                level = "4.0",
                confidence = BigDecimal("0.50"),
            )
        }
        return user
    }

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    private fun fixtureRequest(
        p1: UUID,
        p2: UUID,
        date: LocalDate = LocalDate.parse("2026-01-01"),
        matchType: MatchType = MatchType.OPEN_PLAY,
    ) = FixtureInput(
        matchFormat = TeamType.SINGLES,
        matchType = matchType,
        matchDate = date,
        team1 = listOf(element = p1),
        team2 = listOf(element = p2),
    )

    private fun straightSets() =
        MatchResultRequest(
            sets =
                listOf(
                    SetScoreRequest(team1Games = 6, team2Games = 4),
                    SetScoreRequest(team1Games = 6, team2Games = 3),
                ),
        )

    @Test
    fun `a host can create a fixture between rated players`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)

        val match =
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()

        match.status shouldBe MatchStatus.SCHEDULED
        match.team1.userIds shouldBe listOf(p1.id)
    }

    @Test
    fun `non-staff cannot create a fixture`() {
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)

        service
            .createFixture(token = token(uid = "p1"), request = fixtureRequest(p1 = p1.id, p2 = p2.id))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .createFixture(token = token(uid = "ghost"), request = fixtureRequest(p1 = p1.id, p2 = p2.id))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `a fixture rejects an unrated participant`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val rated = provisionUser(uid = "p1", rated = true)
        val unrated = provisionUser(uid = "p2", rated = false)

        // Composition validation (players-per-side, no repeats) is now a route concern (#116); the
        // service still enforces the business rule that every participant must already have a rating.
        service
            .createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = rated.id, p2 = unrated.id))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `uploading results derives the winner and completes the match`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match =
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()

        val completed = service.uploadResult(token = token(uid = "host"), matchId = match.id, request = straightSets()).shouldBeRight()

        completed.status shouldBe MatchStatus.COMPLETED
        completed.winnerTeamId shouldBe match.team1.teamId // won both sets
    }

    @Test
    fun `a one-set result is accepted, decided by that set`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match =
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()

        // A match is decided by set majority; a single set (4-3) is a valid result.
        val completed =
            service
                .uploadResult(
                    token = token(uid = "host"),
                    matchId = match.id,
                    request = MatchResultRequest(sets = listOf(element = SetScoreRequest(team1Games = 4, team2Games = 3))),
                ).shouldBeRight()

        completed.status shouldBe MatchStatus.COMPLETED
        completed.winnerTeamId shouldBe match.team1.teamId
    }

    @Test
    fun `re-recording an unrated match edits the result, replacing its sets`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match =
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()
        service.uploadResult(token = token(uid = "host"), matchId = match.id, request = straightSets()).shouldBeRight()

        // A second upload while unrated is an edit, not a conflict; the sets are overwritten (not appended).
        val edited =
            service.uploadResult(token = token(uid = "host"), matchId = match.id, request = straightSets()).shouldBeRight()
        edited.status shouldBe MatchStatus.COMPLETED
        edited.sets shouldHaveSize 2
    }

    @Test
    fun `uploading to a disabled or already-rated match is a conflict`() {
        val host = provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)

        val disabled =
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()
        service.setActive(token = token(uid = "host"), matchId = disabled.id, active = false).shouldBeRight()
        service
            .uploadResult(token = token(uid = "host"), matchId = disabled.id, request = straightSets())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()

        val rated =
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()
        service.uploadResult(token = token(uid = "host"), matchId = rated.id, request = straightSets()).shouldBeRight()
        matchRepo.markRated(matchId = rated.id, ratedAt = LocalDateTime.now(), ratedBy = host.id)
        service
            .uploadResult(token = token(uid = "host"), matchId = rated.id, request = straightSets())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `a result with no clear winner is rejected`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match =
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()

        // one set each → tie, no match winner
        service
            .uploadResult(
                token = token(uid = "host"),
                matchId = match.id,
                request =
                    MatchResultRequest(
                        sets =
                            listOf(
                                SetScoreRequest(team1Games = 6, team2Games = 0),
                                SetScoreRequest(team1Games = 0, team2Games = 6),
                            ),
                    ),
            ).shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `a rated match cannot be disabled`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match =
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()
        service.uploadResult(token = token(uid = "host"), matchId = match.id, request = straightSets()).shouldBeRight()
        // simulate the calculation trigger having rated it
        transaction {
            MatchesTable.update(where = { MatchesTable.id eq match.id }) {
                it[MatchesTable.ratedAt] = java.time.LocalDateTime.now()
            }
        }

        service
            .setActive(token = token(uid = "host"), matchId = match.id, active = false)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    @Test
    fun `reading a match is limited to participants and staff`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        provisionUser(uid = "outsider")
        val match =
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()

        service.getById(token = token(uid = "p1"), matchId = match.id).shouldBeRight().id shouldBe match.id // participant
        service.getById(token = token(uid = "host"), matchId = match.id).shouldBeRight().id shouldBe match.id // staff
        service
            .getById(token = token(uid = "outsider"), matchId = match.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
        service
            .getById(token = token(uid = "host"), matchId = UUID.randomUUID())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `creating a fixture and uploading a result write audit-log entries (#100)`() {
        val host = provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match =
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()
        service.uploadResult(token = token(uid = "host"), matchId = match.id, request = straightSets()).shouldBeRight()

        val audit = AuditRepository()
        audit.list(actions = listOf(element = AuditAction.MATCH_FIXTURE_CREATED), limit = 10, offset = 0).first.single().let {
            it.actorUserId shouldBe host.id
            it.entityId shouldBe match.id
            it.summary shouldBe "Created a SINGLES fixture on 2026-01-01"
        }
        audit.list(actions = listOf(element = AuditAction.MATCH_RESULT_RECORDED), limit = 10, offset = 0).first.single().let {
            it.actorUserId shouldBe host.id
            it.entityId shouldBe match.id
        }
    }

    @Test
    fun `calculationDetail returns the match plus the stored per-player calculation`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match =
            service.createFixture(token = token(uid = "root"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()
        service.uploadResult(token = token(uid = "root"), matchId = match.id, request = straightSets()).shouldBeRight()
        calc.calculate(token = token(uid = "root"), dryRun = false) // commit, persisting the breakdown

        // A participant can read their own match's calculation detail.
        val detail = service.calculationDetail(token = token(uid = "p1"), matchId = match.id).shouldBeRight()
        detail.match.id shouldBe match.id
        detail.players.map { it.userId } shouldBe listOf(p1.id, p2.id) // team1-then-team2 order

        val winner = detail.players.first { it.userId == p1.id }
        winner.displayName shouldBe "p1"
        // v2 (default) persists per-set steps; the net breakdown fields stay null (#110).
        winner.history.kFactor.shouldBeNull()
        winner.history.setBreakdown.size shouldBe 2
        winner.history.setBreakdown.first().let { set ->
            set.kFactor shouldBe "0.160000"
            set.competitiveThresholdPct shouldBe "0.083000"
            set.isUpset shouldBe false
        }
        // The persisted new rating matches the committed history (faithful, not recomputed).
        winner.history.newRating shouldBe ratings.findCurrentRating(userId = p1.id)!!.currentRating
    }

    @Test
    fun `calculationDetail is gated to participants and staff and 404s without a calculation`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        provisionUser(uid = "outsider")
        val match =
            service.createFixture(token = token(uid = "root"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()
        service.uploadResult(token = token(uid = "root"), matchId = match.id, request = straightSets()).shouldBeRight()

        // Completed but not yet calculated → no stored calculation to show.
        service
            .calculationDetail(token = token(uid = "root"), matchId = match.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()

        calc.calculate(token = token(uid = "root"), dryRun = false)
        // A non-participant, non-staff caller is refused.
        service
            .calculationDetail(token = token(uid = "outsider"), matchId = match.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `a fixture rejects an unknown participant id`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val ghostId = UUID.randomUUID()

        service
            .createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = ghostId))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `a fixture rejects an inactive participant`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        users.deactivate(id = p2.id)

        service
            .createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `uploading results to an unknown match is not found`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        service
            .uploadResult(token = token(uid = "host"), matchId = UUID.randomUUID(), request = straightSets())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `disabling an unknown match is not found`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        service
            .setActive(token = token(uid = "host"), matchId = UUID.randomUUID(), active = false)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `a host can disable then re-enable a match`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match =
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()

        service.setActive(token = token(uid = "host"), matchId = match.id, active = false).shouldBeRight().isActive shouldBe false
        service.setActive(token = token(uid = "host"), matchId = match.id, active = true).shouldBeRight().isActive shouldBe true
    }

    @Test
    fun `reading a match refuses an unprovisioned caller`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match =
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()

        service
            .getById(token = token(uid = "ghost"), matchId = match.id)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `oversight queries are staff-only, and a host is scoped to their own fixtures`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val completed =
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()
        service.uploadResult(token = token(uid = "host"), matchId = completed.id, request = straightSets()).shouldBeRight()
        val overdue =
            service
                .createFixture(
                    token = token(uid = "host"),
                    request = fixtureRequest(p1 = p1.id, p2 = p2.id, date = LocalDate.parse("2020-01-01")),
                ).shouldBeRight()

        // Admin sees every match in the view.
        service
            .query(token = token(uid = "root"), view = MatchQuery.PENDING_CALCULATION)
            .shouldBeRight()
            .map { it.id } shouldContain completed.id
        service
            .query(token = token(uid = "root"), view = MatchQuery.AWAITING_RESULTS)
            .shouldBeRight()
            .map { it.id } shouldContain overdue.id
        // The host that created them can see their own.
        service
            .query(token = token(uid = "host"), view = MatchQuery.PENDING_CALCULATION)
            .shouldBeRight()
            .map { it.id } shouldContain completed.id
        service
            .query(token = token(uid = "host"), view = MatchQuery.AWAITING_RESULTS)
            .shouldBeRight()
            .map { it.id } shouldContain overdue.id
        // A non-staff player is refused.
        service
            .query(token = token(uid = "p1"), view = MatchQuery.PENDING_CALCULATION)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    @Test
    fun `a created fixture gets a 6-char public code (#136)`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)

        val match =
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()
        match.publicCode.length shouldBe 6
    }

    @Test
    fun `publicByCode returns a read-only summary with resolved players, or NotFound (#136)`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match =
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()
        service.uploadResult(token = token(uid = "host"), matchId = match.id, request = straightSets()).shouldBeRight()

        val public = service.publicByCode(code = match.publicCode).shouldBeRight()
        public.publicCode shouldBe match.publicCode
        public.team1.single().displayName shouldBe "p1"
        public.team1.single().publicCode shouldBe p1.publicCode
        public.team2.single().displayName shouldBe "p2"
        public.winner shouldBe "TEAM1" // p1 won both sets
        public.sets shouldHaveSize 2

        service.publicByCode(code = "ZZZZZZ").shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `a fixture scoped to an event requires both players to be participants (#138)`() {
        val host = provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val outsider = provisionUser(uid = "p3", rated = true)
        val event =
            EventRepository().create(
                command =
                    CreateEventCommand(
                        name = "E",
                        startDate = LocalDate.parse("2026-01-01"),
                        endDate = LocalDate.parse("2026-01-02"),
                        participantIds = listOf(p1.id, p2.id),
                        createdBy = host.id,
                    ),
            )

        // Both players are participants → linked to the event.
        val ok =
            service
                .createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id).copy(eventId = event.id))
                .shouldBeRight()
        ok.eventId shouldBe event.id

        // A non-participant → rejected.
        service
            .createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = outsider.id).copy(eventId = event.id))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()

        // An unknown event → rejected.
        service
            .createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id).copy(eventId = UUID.randomUUID()))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `awaiting-results can be scoped to a single event (#138)`() {
        val host = provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val event =
            EventRepository().create(
                command =
                    CreateEventCommand(
                        name = "E",
                        startDate = LocalDate.parse("2026-01-01"),
                        endDate = LocalDate.parse("2026-01-02"),
                        participantIds = listOf(p1.id, p2.id),
                        createdBy = host.id,
                    ),
            )
        val inEvent =
            service
                .createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id).copy(eventId = event.id))
                .shouldBeRight()
        // A fixture not in any event.
        service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()

        val scoped = service.query(token = token(uid = "host"), view = MatchQuery.AWAITING_RESULTS, eventId = event.id).shouldBeRight()
        scoped.map { it.id } shouldBe listOf(element = inEvent.id)
    }

    @Test
    fun `pending-calculation can be scoped to a single event, listing its recorded fixtures (#138)`() {
        val host = provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val event =
            EventRepository().create(
                command =
                    CreateEventCommand(
                        name = "E",
                        startDate = LocalDate.parse("2026-01-01"),
                        endDate = LocalDate.parse("2026-01-02"),
                        participantIds = listOf(p1.id, p2.id),
                        createdBy = host.id,
                    ),
            )
        val inEvent =
            service
                .createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id).copy(eventId = event.id))
                .shouldBeRight()
        service.uploadResult(token = token(uid = "host"), matchId = inEvent.id, request = straightSets()).shouldBeRight()
        // A recorded fixture outside the event must not leak into the event-scoped view.
        val outside =
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()
        service.uploadResult(token = token(uid = "host"), matchId = outside.id, request = straightSets()).shouldBeRight()

        val scoped =
            service.query(token = token(uid = "host"), view = MatchQuery.PENDING_CALCULATION, eventId = event.id).shouldBeRight()
        scoped.map { it.id } shouldBe listOf(element = inEvent.id)
    }

    @Test
    fun `publicByCode excludes a disabled match (#136)`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match =
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()
        service.setActive(token = token(uid = "host"), matchId = match.id, active = false).shouldBeRight()

        service.publicByCode(code = match.publicCode).shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }
}
