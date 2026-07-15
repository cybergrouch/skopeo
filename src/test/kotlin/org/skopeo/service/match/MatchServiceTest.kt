// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.match

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
import org.skopeo.model.Match
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

    private fun create(
        host: String,
        request: FixtureInput,
    ) = service.createFixture(token = token(uid = host), request = request).shouldBeRight()

    @Test
    fun `upcomingForCaller returns only the caller's future scheduled matches, soonest first (#251)`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val p3 = provisionUser(uid = "p3", rated = true)
        val p4 = provisionUser(uid = "p4", rated = true)
        val today = LocalDate.now()

        val soon = create(host = "host", request = fixtureRequest(p1 = p1.id, p2 = p2.id, date = today.plusDays(3)))
        val later = create(host = "host", request = fixtureRequest(p1 = p1.id, p2 = p3.id, date = today.plusDays(10)))
        // Caller sits on team2 here — the opponent must be resolved from the other side.
        val onTeam2 = create(host = "host", request = fixtureRequest(p1 = p4.id, p2 = p1.id, date = today.plusDays(5)))
        // Excluded: a past scheduled fixture, and a future fixture that's already completed.
        create(host = "host", request = fixtureRequest(p1 = p1.id, p2 = p2.id, date = today.minusDays(2)))
        val done = create(host = "host", request = fixtureRequest(p1 = p1.id, p2 = p3.id, date = today.plusDays(1)))
        service.uploadResult(token = token(uid = "host"), matchId = done.id, request = straightSets()).shouldBeRight()

        val upcoming = service.upcomingForCaller(token = token(uid = "p1")).shouldBeRight()

        upcoming.map { it.publicCode } shouldBe listOf(soon.publicCode, onTeam2.publicCode, later.publicCode)
        upcoming.first().opponents.single().publicCode shouldBe p2.publicCode
        upcoming.single { it.publicCode == onTeam2.publicCode }.opponents.single().publicCode shouldBe p4.publicCode
    }

    @Test
    fun `upcomingForCaller is empty for an unprovisioned caller (#251)`() {
        service.upcomingForCaller(token = token(uid = "ghost")).shouldBeRight() shouldBe emptyList()
    }

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

        val public = service.publicByCode(token = token(uid = "host"), code = match.publicCode).shouldBeRight()
        public.publicCode shouldBe match.publicCode
        public.team1.single().displayName shouldBe "p1"
        public.team1.single().publicCode shouldBe p1.publicCode
        public.team2.single().displayName shouldBe "p2"
        public.winner shouldBe "TEAM1" // p1 won both sets
        public.sets shouldHaveSize 2
        public.ratingChanges.shouldBeNull() // not yet calculated → no rating changes

        service
            .publicByCode(token = token(uid = "host"), code = "ZZZZZZ")
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `publicByCode includes the head-to-head record between the two players (#188)`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)

        fun completed(
            team1: UUID,
            team2: UUID,
            date: String,
            result: MatchResultRequest,
        ): Match {
            val m =
                service
                    .createFixture(
                        token = token(uid = "host"),
                        request = fixtureRequest(p1 = team1, p2 = team2, date = LocalDate.parse(date)),
                    ).shouldBeRight()
            service.uploadResult(token = token(uid = "host"), matchId = m.id, request = result).shouldBeRight()
            return m
        }
        val p2Wins =
            MatchResultRequest(
                sets =
                    listOf(
                        SetScoreRequest(team1Games = 4, team2Games = 6),
                        SetScoreRequest(team1Games = 3, team2Games = 6),
                    ),
            )

        // Prior meetings, plus the one being viewed — all between p1 and p2.
        completed(team1 = p1.id, team2 = p2.id, date = "2026-01-01", result = straightSets()) // p1 won
        completed(team1 = p1.id, team2 = p2.id, date = "2026-02-01", result = p2Wins) // p2 won
        completed(team1 = p2.id, team2 = p1.id, date = "2025-12-01", result = straightSets()) // reversed sides, p2 won
        // A scheduled (not yet completed) meeting between them is excluded from the head-to-head.
        service
            .createFixture(
                token = token(uid = "host"),
                request = fixtureRequest(p1 = p1.id, p2 = p2.id, date = LocalDate.parse("2026-04-01")),
            ).shouldBeRight()
        val current = completed(team1 = p1.id, team2 = p2.id, date = "2026-03-01", result = straightSets())

        val public = service.publicByCode(token = token(uid = "host"), code = current.publicCode).shouldBeRight()
        val h2h = public.headToHead.shouldNotBeNull()

        // The meetings list is the prior meetings only (the viewed match isn't repeated), newest first.
        h2h.meetings.map { it.matchDate } shouldBe listOf("2026-02-01", "2026-01-01", "2025-12-01")
        // …but the win tally includes the viewed March match, which p1 won (#339).
        h2h.team1Wins shouldBe 2 // p1: the Jan meeting + the viewed March match
        h2h.team2Wins shouldBe 2 // p2: the Feb + reversed-Dec meetings

        // Winner resolved to the player's code; set scores oriented to the viewed match's team1 (p1).
        val feb = h2h.meetings.first()
        feb.winnerPublicCode shouldBe p2.publicCode
        feb.sets.first().team1Games shouldBe 4 // p1's games
        feb.sets.first().team2Games shouldBe 6

        val dec = h2h.meetings.last() // sides were reversed; oriented back to p1
        dec.winnerPublicCode shouldBe p2.publicCode
        dec.sets.first().team1Games shouldBe 4 // p1's games (flipped from the stored team2 side)
        dec.sets.first().team2Games shouldBe 6
    }

    @Test
    fun `head-to-head counts and labels prior doubles meetings between the two players (#285)`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val p3 = provisionUser(uid = "p3", rated = true)
        val p4 = provisionUser(uid = "p4", rated = true)

        // A prior DOUBLES meeting where p1 & p2 were opponents; p1's side (team1) won.
        val dbl =
            service
                .createFixture(
                    token = token(uid = "host"),
                    request =
                        FixtureInput(
                            matchFormat = TeamType.DOUBLES,
                            matchType = MatchType.OPEN_PLAY,
                            matchDate = LocalDate.parse("2026-01-15"),
                            team1 = listOf(p1.id, p3.id),
                            team2 = listOf(p2.id, p4.id),
                        ),
                ).shouldBeRight()
        service.uploadResult(token = token(uid = "host"), matchId = dbl.id, request = straightSets()).shouldBeRight()

        // A prior SINGLES meeting p2 won (team1 = p2), then the current singles match p1 vs p2.
        val singlesMeeting =
            service
                .createFixture(
                    token = token(uid = "host"),
                    request = fixtureRequest(p1 = p2.id, p2 = p1.id, date = LocalDate.parse("2026-02-01")),
                )
                .shouldBeRight()
        service.uploadResult(token = token(uid = "host"), matchId = singlesMeeting.id, request = straightSets()).shouldBeRight()
        val current =
            service
                .createFixture(
                    token = token(uid = "host"),
                    request = fixtureRequest(p1 = p1.id, p2 = p2.id, date = LocalDate.parse("2026-03-01")),
                )
                .shouldBeRight()
        service.uploadResult(token = token(uid = "host"), matchId = current.id, request = straightSets()).shouldBeRight()

        val h2h = service.publicByCode(token = token(uid = "host"), code = current.publicCode).shouldBeRight().headToHead.shouldNotBeNull()

        // Both prior meetings count — previously the doubles one was silently dropped from the tally (#285).
        h2h.meetings shouldHaveSize 2
        // The tally also includes the current March match, which p1 won (#339).
        h2h.team1Wins shouldBe 2 // p1: the prior doubles win + the current match
        h2h.team2Wins shouldBe 1 // p2: the prior singles win
        val doublesEntry = h2h.meetings.single { it.matchFormat == "DOUBLES" }
        doublesEntry.winnerPublicCode shouldBe p1.publicCode
        h2h.meetings.count { it.matchFormat == "SINGLES" } shouldBe 1
    }

    @Test
    fun `publicByCode omits the head-to-head when there are no prior meetings (#188)`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match =
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()
        service.uploadResult(token = token(uid = "host"), matchId = match.id, request = straightSets()).shouldBeRight()

        service.publicByCode(token = token(uid = "host"), code = match.publicCode).shouldBeRight().headToHead.shouldBeNull()
    }

    @Test
    fun `publicByCode omits the head-to-head for a non-singles match (#188)`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val p3 = provisionUser(uid = "p3", rated = true)

        fun doublesFixture(
            team1: List<UUID>,
            team2: List<UUID>,
        ) = service
            .createFixture(
                token = token(uid = "host"),
                request =
                    FixtureInput(
                        matchFormat = TeamType.DOUBLES,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = LocalDate.parse("2026-01-01"),
                        team1 = team1,
                        team2 = team2,
                    ),
            ).shouldBeRight()

        // Either side having more than one player → head-to-head (singles-only) does not apply.
        val pluralTeam1 = doublesFixture(team1 = listOf(element = p1.id) + p2.id, team2 = listOf(element = p3.id))
        val pluralTeam2 = doublesFixture(team1 = listOf(element = p1.id), team2 = listOf(element = p2.id) + p3.id)

        service.publicByCode(token = token(uid = "host"), code = pluralTeam1.publicCode).shouldBeRight().headToHead.shouldBeNull()
        service.publicByCode(token = token(uid = "host"), code = pluralTeam2.publicCode).shouldBeRight().headToHead.shouldBeNull()
    }

    @Test
    fun `publicByCode reveals rating changes once rated, gated by viewer capability (#136)`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provisionUser(uid = "viewer") // a plain player — no rate-visibility capability
        provisionUser(uid = "rater", roles = setOf(Capability.PLAYER, Capability.RATER))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match =
            service.createFixture(token = token(uid = "root"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()
        service.uploadResult(token = token(uid = "root"), matchId = match.id, request = straightSets()).shouldBeRight()
        calc.calculate(token = token(uid = "root"), dryRun = false) // commit ratings + history

        // A non-rater viewer sees the NTRP bands only — the precise rates are withheld.
        val asPlayer = service.publicByCode(token = token(uid = "viewer"), code = match.publicCode).shouldBeRight()
        val changes = asPlayer.ratingChanges.shouldNotBeNull()
        changes shouldHaveSize 2
        changes.map { it.displayName } shouldBe listOf("p1", "p2") // team1-then-team2 order
        changes.forEach { change ->
            change.previousLevel.shouldNotBeNull()
            change.newLevel.shouldNotBeNull()
            change.previousRating.shouldBeNull()
            change.newRating.shouldBeNull()
            change.ratingChange.shouldBeNull()
        }

        // A viewer with a valid token but no provisioned Skopeo profile still gets the public bands, no rates.
        val asGhost =
            service.publicByCode(token = token(uid = "ghost"), code = match.publicCode).shouldBeRight().ratingChanges.shouldNotBeNull()
        asGhost.forEach { change ->
            change.newLevel.shouldNotBeNull()
            change.newRating.shouldBeNull()
        }

        // An anonymous viewer (no token, #193) is treated like any non-privileged viewer: bands, no rates.
        val asAnon =
            service.publicByCode(token = null, code = match.publicCode).shouldBeRight().ratingChanges.shouldNotBeNull()
        asAnon.forEach { change ->
            change.newLevel.shouldNotBeNull()
            change.newRating.shouldBeNull()
        }

        // Both a RATER and an ADMINISTRATOR see the precise 6-dp rates.
        for (uid in listOf("rater", "root")) {
            val staffChanges =
                service.publicByCode(token = token(uid = uid), code = match.publicCode).shouldBeRight().ratingChanges.shouldNotBeNull()
            staffChanges.forEach { change ->
                change.previousRating.shouldNotBeNull()
                change.newRating.shouldNotBeNull()
                change.ratingChange.shouldNotBeNull()
            }
            // The fractional part carries 6 digits, matching the NUMERIC(10,6) storage.
            staffChanges.first().newRating.shouldNotBeNull().substringAfter(delimiter = ".").length shouldBe 6
        }
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
                        startDate = LocalDate.now(),
                        endDate = LocalDate.now().plusDays(7),
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
    fun `a host cannot create a fixture or record a result on an expired event, an admin or club owner can (#310)`() {
        val host = provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provisionUser(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provisionUser(uid = "owner", roles = setOf(Capability.PLAYER, Capability.CLUB_OWNER))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val event =
            EventRepository().create(
                command =
                    CreateEventCommand(
                        name = "Ended",
                        startDate = LocalDate.now().minusDays(3),
                        endDate = LocalDate.now().minusDays(1),
                        participantIds = listOf(p1.id, p2.id),
                        createdBy = host.id,
                    ),
            )

        // HOST blocked from creating a fixture on the ended event.
        service
            .createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id).copy(eventId = event.id))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()

        // ADMINISTRATOR may create it.
        val match =
            service
                .createFixture(token = token(uid = "admin"), request = fixtureRequest(p1 = p1.id, p2 = p2.id).copy(eventId = event.id))
                .shouldBeRight()

        // HOST blocked from recording the result; ADMINISTRATOR may.
        service.uploadResult(token = token(uid = "host"), matchId = match.id, request = straightSets())
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
        service.uploadResult(token = token(uid = "admin"), matchId = match.id, request = straightSets()).shouldBeRight()

        // A CLUB_OWNER is exempt from the expiry gate too (#310 follow-up): it may create + record.
        val ownerMatch =
            service
                .createFixture(token = token(uid = "owner"), request = fixtureRequest(p1 = p1.id, p2 = p2.id).copy(eventId = event.id))
                .shouldBeRight()
        service.uploadResult(token = token(uid = "owner"), matchId = ownerMatch.id, request = straightSets()).shouldBeRight()
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
                        startDate = LocalDate.now(),
                        endDate = LocalDate.now().plusDays(7),
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
                        startDate = LocalDate.now(),
                        endDate = LocalDate.now().plusDays(7),
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
    fun `results lists an event's completed fixtures, rated or not, but pending-calculation drops the rated one (#138)`() {
        val host = provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val event =
            EventRepository().create(
                command =
                    CreateEventCommand(
                        name = "E",
                        startDate = LocalDate.now(),
                        endDate = LocalDate.now().plusDays(7),
                        participantIds = listOf(p1.id, p2.id),
                        createdBy = host.id,
                    ),
            )
        val recorded =
            service
                .createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id).copy(eventId = event.id))
                .shouldBeRight()
        service.uploadResult(token = token(uid = "host"), matchId = recorded.id, request = straightSets()).shouldBeRight()
        val rated =
            service
                .createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id).copy(eventId = event.id))
                .shouldBeRight()
        service.uploadResult(token = token(uid = "host"), matchId = rated.id, request = straightSets()).shouldBeRight()
        matchRepo.markRated(matchId = rated.id, ratedAt = LocalDateTime.now(), ratedBy = host.id)

        // RESULTS keeps the rated match on view alongside the recorded one...
        val results = service.query(token = token(uid = "host"), view = MatchQuery.RESULTS, eventId = event.id).shouldBeRight()
        results.map { it.id }.toSet() shouldBe setOf(recorded.id, rated.id)
        // ...while pending-calculation only surfaces the still-unrated one.
        val pending =
            service.query(token = token(uid = "host"), view = MatchQuery.PENDING_CALCULATION, eventId = event.id).shouldBeRight()
        pending.map { it.id } shouldBe listOf(element = recorded.id)
    }

    @Test
    fun `results without an event id is empty`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        service.query(token = token(uid = "host"), view = MatchQuery.RESULTS).shouldBeRight() shouldBe emptyList()
    }

    @Test
    fun `publicByCode still resolves a disabled match, flagged as deleted (#136, #325)`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match =
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()
        service.setActive(token = token(uid = "host"), matchId = match.id, active = false).shouldBeRight()

        // Soft-deleted matches stay traceable (#325): the link resolves and is flagged not-active.
        val public = service.publicByCode(token = token(uid = "host"), code = match.publicCode).shouldBeRight()
        public.publicCode shouldBe match.publicCode
        public.isActive shouldBe false
    }

    @Test
    fun `match history includes a soft-deleted match, flagged not-active (#325)`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match =
            service.createFixture(token = token(uid = "host"), request = fixtureRequest(p1 = p1.id, p2 = p2.id)).shouldBeRight()
        service.setActive(token = token(uid = "host"), matchId = match.id, active = false).shouldBeRight()

        matchRepo.listByUser(userId = p1.id).map { it.id } shouldContain match.id
        matchRepo.listByUser(userId = p1.id).single { it.id == match.id }.isActive shouldBe false
    }

    @Test
    fun `reorder assigns calc sequence to same-date matches in the given order (#331, #332)`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val p3 = provisionUser(uid = "p3", rated = true)
        val date = LocalDate.parse("2026-02-01")
        val m1 = create(host = "host", request = fixtureRequest(p1 = p1.id, p2 = p2.id, date = date))
        val m2 = create(host = "host", request = fixtureRequest(p1 = p1.id, p2 = p3.id, date = date))

        service.reorder(token = token(uid = "host"), matchIds = listOf(m2.id, m1.id)).shouldBeRight()

        matchRepo.findById(matchId = m2.id).shouldBeRight().calcSequence shouldBe 0
        matchRepo.findById(matchId = m1.id).shouldBeRight().calcSequence shouldBe 1
    }

    @Test
    fun `reorder rejects empty, duplicate, cross-date, unknown, and non-staff requests (#332)`() {
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provisionUser(uid = "player")
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val m1 = create(host = "host", request = fixtureRequest(p1 = p1.id, p2 = p2.id, date = LocalDate.parse("2026-02-01")))
        val m2 = create(host = "host", request = fixtureRequest(p1 = p1.id, p2 = p2.id, date = LocalDate.parse("2026-02-02")))

        service.reorder(token = token(uid = "player"), matchIds = listOf(element = m1.id))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        service.reorder(token = token(uid = "host"), matchIds = emptyList())
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.reorder(token = token(uid = "host"), matchIds = listOf(m1.id, m1.id))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.reorder(token = token(uid = "host"), matchIds = listOf(m1.id, m2.id))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
        service.reorder(token = token(uid = "host"), matchIds = listOf(element = UUID.randomUUID()))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.NotFound>()
    }

    @Test
    fun `reorder refuses a match that has already been rated (#332)`() {
        val host = provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match = create(host = "host", request = fixtureRequest(p1 = p1.id, p2 = p2.id))
        service.uploadResult(token = token(uid = "host"), matchId = match.id, request = straightSets()).shouldBeRight()
        matchRepo.markRated(matchId = match.id, ratedAt = LocalDateTime.now(), ratedBy = host.id)

        service.reorder(token = token(uid = "host"), matchIds = listOf(element = match.id))
            .shouldBeLeft().shouldBeInstanceOf<ServiceError.Conflict>()
    }
}
