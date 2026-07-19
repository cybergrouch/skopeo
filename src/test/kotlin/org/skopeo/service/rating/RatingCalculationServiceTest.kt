// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.rating

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.dto.RankingCalculationRequest
import org.skopeo.dto.RankingCalculationResponse
import org.skopeo.dto.match.MatchResultRequest
import org.skopeo.dto.match.SetScoreRequest
import org.skopeo.model.AuditAction
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.CreateEventCommand
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
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRatingHistoryTable
import org.skopeo.repository.UserRatingsTable
import org.skopeo.repository.UserRepository
import org.skopeo.service.calculator.RankingCalculationResult
import org.skopeo.service.calculator.RankingCalculator
import org.skopeo.service.calculator.impl.v2.PerformanceBasedRankingCalculatorImpl
import org.skopeo.service.match.FixtureInput
import org.skopeo.service.match.MatchService
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import io.kotest.matchers.string.shouldContain as stringShouldContain

class RatingCalculationServiceTest {
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
    private val matchService = MatchService(matches = matchRepo, ratings = ratings, users = users)
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

    /** Create + complete a fixture where [winner] beats [loser]; returns the match id. */
    private fun playedMatch(
        admin: String,
        winner: UUID,
        loser: UUID,
        matchType: MatchType = MatchType.OPEN_PLAY,
    ): UUID {
        val match =
            matchService.createFixture(
                token = token(uid = admin),
                request =
                    FixtureInput(
                        matchFormat = TeamType.SINGLES,
                        matchType = matchType,
                        matchDate = LocalDate.parse("2026-01-01"),
                        team1 = listOf(element = winner),
                        team2 = listOf(element = loser),
                    ),
            ).shouldBeRight()
        matchService.uploadResult(
            token = token(uid = admin),
            matchId = match.id,
            request =
                MatchResultRequest(
                    sets =
                        listOf(
                            SetScoreRequest(team1Games = 6, team2Games = 4),
                            SetScoreRequest(team1Games = 6, team2Games = 2),
                        ),
                ),
        ).shouldBeRight()
        return match.id
    }

    /** Schedule a singles fixture on [date]; returns the match id (no result yet). */
    private fun scheduledOn(
        admin: String,
        a: UUID,
        b: UUID,
        date: LocalDate,
    ): UUID =
        matchService.createFixture(
            token = token(uid = admin),
            request =
                FixtureInput(
                    matchFormat = TeamType.SINGLES,
                    matchType = MatchType.OPEN_PLAY,
                    matchDate = date,
                    team1 = listOf(element = a),
                    team2 = listOf(element = b),
                ),
        ).shouldBeRight().id

    private fun recordResult(
        admin: String,
        matchId: UUID,
    ) {
        matchService.uploadResult(
            token = token(uid = admin),
            matchId = matchId,
            request =
                MatchResultRequest(
                    sets =
                        listOf(
                            SetScoreRequest(team1Games = 6, team2Games = 4),
                            SetScoreRequest(team1Games = 6, team2Games = 2),
                        ),
                ),
        ).shouldBeRight()
    }

    @Test
    fun `processes matches in match-date order, not result-entry order (#331)`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val p3 = provisionUser(uid = "p3", rated = true)
        val p4 = provisionUser(uid = "p4", rated = true)
        // 'later' is played on a later date but its result is entered first (earlier completedAt).
        val later = scheduledOn(admin = "root", a = p3.id, b = p4.id, date = LocalDate.parse("2026-01-02"))
        val earlier = scheduledOn(admin = "root", a = p1.id, b = p2.id, date = LocalDate.parse("2026-01-01"))
        recordResult(admin = "root", matchId = later)
        recordResult(admin = "root", matchId = earlier)

        val order = calc.calculate(token = token(uid = "root"), dryRun = true).shouldBeRight().matches.map { it.matchId }

        // Match date wins over result-entry (completed_at) order.
        order shouldBe listOf(earlier, later)
    }

    @Test
    fun `a manual reorder re-sequences same-date matches for calculation (#332)`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val p3 = provisionUser(uid = "p3", rated = true)
        val p4 = provisionUser(uid = "p4", rated = true)
        val date = LocalDate.parse("2026-01-01")
        val m1 = scheduledOn(admin = "root", a = p1.id, b = p2.id, date = date)
        val m2 = scheduledOn(admin = "root", a = p3.id, b = p4.id, date = date)
        recordResult(admin = "root", matchId = m1)
        recordResult(admin = "root", matchId = m2)

        // A manual drag sets the exact same-date processing order (no reliance on the completed_at tiebreak).
        matchService.reorder(token = token(uid = "root"), matchIds = listOf(m2, m1)).shouldBeRight()
        calc.calculate(token = token(uid = "root"), dryRun = true).shouldBeRight().matches.map { it.matchId } shouldBe listOf(m2, m1)

        matchService.reorder(token = token(uid = "root"), matchIds = listOf(m1, m2)).shouldBeRight()
        calc.calculate(token = token(uid = "root"), dryRun = true).shouldBeRight().matches.map { it.matchId } shouldBe listOf(m1, m2)
    }

    @Test
    fun `recalculates a match recorded with a flexible, win-by-one set score`() {
        // Regression: recording accepts flexible scores (#213), but the calculation rebuilt each set
        // through SetScore, which enforced a stricter tennis format (win-by-2) and threw during recalc.
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val match =
            matchService.createFixture(
                token = token(uid = "root"),
                request =
                    FixtureInput(
                        matchFormat = TeamType.SINGLES,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = LocalDate.parse("2026-01-01"),
                        team1 = listOf(element = p1.id),
                        team2 = listOf(element = p2.id),
                    ),
            ).shouldBeRight()
        matchService.uploadResult(
            token = token(uid = "root"),
            matchId = match.id,
            // 6-5 (won by one) — previously rejected by SetScore's win-by-2 rule during the rebuild.
            request = MatchResultRequest(sets = listOf(element = SetScoreRequest(team1Games = 6, team2Games = 5))),
        ).shouldBeRight()

        val result = calc.calculate(token = token(uid = "root"), dryRun = true).shouldBeRight()

        result.matches.single().changes.size shouldBe 2
    }

    @Test
    fun `dry-run previews changes without writing`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val matchId = playedMatch(admin = "root", winner = p1.id, loser = p2.id)

        val dry = calc.calculate(token = token(uid = "root"), dryRun = true).shouldBeRight()

        dry.dryRun.shouldBeTrue()
        dry.matches.single().changes.size shouldBe 2
        dry.matches.single().changes.first { it.userId == p1.id }.let {
            it.previousRating shouldBe BigDecimal("4.000000")
            (it.newRating > BigDecimal("4.000000")).shouldBeTrue() // winner gains
            // The v2 calculator (default) reports per-set steps; the net fields are null (#110).
            it.breakdown.kFactor.shouldBeNull()
            it.breakdown.dominance.shouldBeNull()
            // One per-set step per set (the fixture has two sets), ordered by set index.
            it.breakdown.sets.size shouldBe 2
            it.breakdown.sets.map { set -> set.setIndex } shouldBe listOf(0, 1)
            it.breakdown.sets.first().let { set ->
                set.score shouldBe "6-4"
                set.kFactor shouldBe "0.160000"
                set.competitiveThresholdPct shouldBe "0.083000"
                set.isUpset.shouldBeFalse() // equal ratings, favourite-ish win is not an upset
                set.dominance.isNotBlank().shouldBeTrue()
                set.scale.isNotBlank().shouldBeTrue()
            }
        }
        // nothing persisted
        ratings.findCurrentRating(userId = p1.id)!!.currentRating shouldBe BigDecimal("4.000000")
        matchRepo.findById(matchId = matchId).shouldBeRight().ratedAt.shouldBeNull()
        matchRepo.listPendingCalculation().size shouldBe 1
    }

    @Test
    fun `a higher-pressure match type moves ratings more than open play (#108)`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val a1 = provisionUser(uid = "a1", rated = true)
        val a2 = provisionUser(uid = "a2", rated = true)
        val b1 = provisionUser(uid = "b1", rated = true)
        val b2 = provisionUser(uid = "b2", rated = true)
        // Two identical matches (equal 4.0 players, same score) differing only by match type.
        playedMatch(admin = "root", winner = a1.id, loser = a2.id, matchType = MatchType.OPEN_PLAY)
        playedMatch(admin = "root", winner = b1.id, loser = b2.id, matchType = MatchType.TOURNAMENT_PLAYOFFS)

        val changes = calc.calculate(token = token(uid = "root"), dryRun = true).shouldBeRight().matches.flatMap { it.changes }
        val openGain = changes.first { it.userId == a1.id }.let { it.newRating - it.previousRating }
        val playoffGain = changes.first { it.userId == b1.id }.let { it.newRating - it.previousRating }

        (openGain > BigDecimal.ZERO).shouldBeTrue()
        // TOURNAMENT_PLAYOFFS (1.2) scales the change well above OPEN_PLAY (0.5) for an identical match.
        (playoffGain > openGain).shouldBeTrue()
    }

    @Test
    fun `a per-side handicap on the loser shrinks their loss, applied to the true rating (#486)`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val a1 = provisionUser(uid = "a1", rated = true)
        val a2 = provisionUser(uid = "a2", rated = true)
        val b1 = provisionUser(uid = "b1", rated = true)
        val b2 = provisionUser(uid = "b2", rated = true)
        // Two identical equal-4.0 dominant wins; one has a 0.3 handicap on the LOSING side (team2).
        playedMatch(admin = "root", winner = a1.id, loser = a2.id, matchType = MatchType.OPEN_PLAY)
        val handicapped =
            matchService.createFixture(
                token = token(uid = "root"),
                request =
                    FixtureInput(
                        matchFormat = TeamType.SINGLES,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = LocalDate.parse("2026-01-01"),
                        team1 = listOf(element = b1.id),
                        team2 = listOf(element = b2.id),
                        team2Handicap = BigDecimal("0.300"),
                    ),
            ).shouldBeRight()
        recordResult(admin = "root", matchId = handicapped.id)

        val changes = calc.calculate(token = token(uid = "root"), dryRun = true).shouldBeRight().matches.flatMap { it.changes }
        val plainLoss = changes.first { it.userId == a2.id }.let { it.newRating - it.previousRating }
        val handicappedLoss = changes.first { it.userId == b2.id }.let { it.newRating - it.previousRating }

        // Both are losses; the handicapped side loses strictly less (closer to zero).
        (plainLoss < BigDecimal.ZERO).shouldBeTrue()
        (handicappedLoss > plainLoss).shouldBeTrue()
        // The delta is applied to the TRUE 4.0 baseline — previous is the true rating, not 3.7.
        changes.first { it.userId == b2.id }.previousRating shouldBe BigDecimal("4.000000")
    }

    @Test
    fun `commit persists ratings, history, and marks matches rated`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val matchId = playedMatch(admin = "root", winner = p1.id, loser = p2.id)

        val committed = calc.calculate(token = token(uid = "root"), dryRun = false).shouldBeRight()
        committed.dryRun.shouldBeFalse()

        ratings.findCurrentRating(userId = p1.id)!!.let {
            (it.currentRating > BigDecimal("4.000000")).shouldBeTrue()
            it.matchesPlayed shouldBe 1
        }
        ratings.historyByUser(userId = p1.id).single().let {
            it.matchId shouldBe matchId
            // The source match's completed_at is snapshotted onto the history row as the ordering
            // tiebreaker (#301); it matches what the match itself records.
            it.completedAt shouldBe matchRepo.findById(matchId = matchId).shouldBeRight().completedAt
            it.completedAt.shouldNotBeNull()
            // The v2 per-set breakdown is persisted at commit and round-trips through the JSON column (#110);
            // the net fields stay null for v2.
            it.kFactor.shouldBeNull()
            it.setBreakdown.size shouldBe 2
            it.setBreakdown.map { set -> set.setIndex } shouldBe listOf(0, 1)
            it.setBreakdown.first().let { set ->
                set.score shouldBe "6-4"
                set.kFactor shouldBe "0.160000"
                set.competitiveThresholdPct shouldBe "0.083000"
                set.isUpset shouldBe false
            }
        }
        matchRepo.findById(matchId = matchId).shouldBeRight().ratedAt.shouldNotBeNull()
        matchRepo.listPendingCalculation().shouldBe(expected = emptyList())

        // idempotent — nothing left to process
        calc.calculate(token = token(uid = "root"), dryRun = false).shouldBeRight().matches.shouldBe(expected = emptyList())
    }

    /** Every persisted rating_run_id (nulls excluded), one entry per history row. */
    private fun persistedRunIds(): List<UUID> =
        transaction {
            UserRatingHistoryTable
                .selectAll()
                .mapNotNull { it[UserRatingHistoryTable.ratingRunId] }
        }

    @Test
    fun `one commit stamps all its rows with a single rating_run_id, a later commit gets a distinct one (#481)`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val p3 = provisionUser(uid = "p3", rated = true)
        val p4 = provisionUser(uid = "p4", rated = true)
        // Two matches (four rows) rated together in one calc run.
        playedMatch(admin = "root", winner = p1.id, loser = p2.id)
        playedMatch(admin = "root", winner = p3.id, loser = p4.id)

        calc.calculate(token = token(uid = "root"), dryRun = false).shouldBeRight()
        val firstRun = persistedRunIds()
        // All four rows of the one run share one non-null id.
        firstRun.size shouldBe 4
        firstRun.toSet().size shouldBe 1

        // A second run over fresh matches produces a distinct run id.
        val p5 = provisionUser(uid = "p5", rated = true)
        val p6 = provisionUser(uid = "p6", rated = true)
        playedMatch(admin = "root", winner = p5.id, loser = p6.id)
        calc.calculate(token = token(uid = "root"), dryRun = false).shouldBeRight()

        val allRunIds = persistedRunIds().toSet()
        // Two distinct runs now; the first run's id is one of them and the second differs from it.
        allRunIds.size shouldBe 2
        allRunIds shouldContain firstRun.first()
    }

    @Test
    fun `an evented match is rated only after the event is finalized, an eventless match is rated as before (#403)`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val p3 = provisionUser(uid = "p3", rated = true)
        val p4 = provisionUser(uid = "p4", rated = true)
        val eventRepo = EventRepository()
        val event =
            eventRepo.create(
                command =
                    CreateEventCommand(
                        name = "E",
                        startDate = LocalDate.parse("2026-01-01"),
                        endDate = LocalDate.parse("2026-01-03"),
                        participantIds = listOf(p1.id, p2.id),
                        createdBy = users.findByFirebaseUid(firebaseUid = "root")!!.id,
                    ),
            )
        // An evented match (recorded while the event is open) and an event-less one.
        val eventedFixture =
            matchService.createFixture(
                token = token(uid = "root"),
                request =
                    FixtureInput(
                        matchFormat = TeamType.SINGLES,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = LocalDate.parse("2026-01-02"),
                        team1 = listOf(element = p1.id),
                        team2 = listOf(element = p2.id),
                        eventId = event.id,
                    ),
            ).shouldBeRight()
        recordResult(admin = "root", matchId = eventedFixture.id)
        val eventless = playedMatch(admin = "root", winner = p3.id, loser = p4.id)

        // Commit while the event is still open: the event-less match is rated, the evented one is not.
        calc.calculate(token = token(uid = "root"), dryRun = false).shouldBeRight()
        matchRepo.findById(matchId = eventless).shouldBeRight().ratedAt.shouldNotBeNull()
        matchRepo.findById(matchId = eventedFixture.id).shouldBeRight().ratedAt.shouldBeNull()

        // After finalizing the event, its match becomes eligible and a commit rates it.
        val root = users.findByFirebaseUid(firebaseUid = "root")!!.id
        eventRepo.finalize(id = event.id, finalizedAt = LocalDateTime.now(), finalizedBy = root)
        calc.calculate(token = token(uid = "root"), dryRun = false).shouldBeRight()
        matchRepo.findById(matchId = eventedFixture.id).shouldBeRight().ratedAt.shouldNotBeNull()
    }

    @Test
    fun `preview logs one summary, commit logs a per-match order trace plus one summary (#333)`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val p3 = provisionUser(uid = "p3", rated = true)
        val p4 = provisionUser(uid = "p4", rated = true)
        playedMatch(admin = "root", winner = p1.id, loser = p2.id)
        playedMatch(admin = "root", winner = p3.id, loser = p4.id)
        val audit = AuditRepository()

        // A dry-run preview writes a single compact summary — no per-match rows, even run twice.
        calc.calculate(token = token(uid = "root"), dryRun = true).shouldBeRight()
        calc.calculate(token = token(uid = "root"), dryRun = true).shouldBeRight()
        audit.list(actions = listOf(element = AuditAction.RATING_CALCULATION_PREVIEWED), limit = 50, offset = 0).let { (rows, total) ->
            total shouldBe 2L
            rows.first().details["matches"] shouldBe "2"
        }
        audit.list(actions = listOf(element = AuditAction.RATING_CALCULATION_MATCH_RATED), limit = 50, offset = 0).second shouldBe 0L

        // Commit writes one per-match row (positions 1..N, the processing ORDER) + one commit summary.
        calc.calculate(token = token(uid = "root"), dryRun = false).shouldBeRight()
        val perMatch = audit.list(actions = listOf(element = AuditAction.RATING_CALCULATION_MATCH_RATED), limit = 50, offset = 0).first
        perMatch.size shouldBe 2
        perMatch.mapNotNull { it.details["position"] }.toSet() shouldBe setOf("1", "2")
        perMatch.all { it.details["totalMatches"] == "2" }.shouldBeTrue()
        audit.list(actions = listOf(element = AuditAction.RATING_CALCULATION_COMMITTED), limit = 50, offset = 0).let { (rows, total) ->
            total shouldBe 1L
            rows.single().details["matches"] shouldBe "2"
        }

        // With nothing left pending, a dry-run preview writes no new summary (the empty-guard branch);
        // the PREVIEWED count stays at the two from earlier.
        calc.calculate(token = token(uid = "root"), dryRun = true).shouldBeRight().matches.shouldBe(expected = emptyList())
        audit.list(actions = listOf(element = AuditAction.RATING_CALCULATION_PREVIEWED), limit = 50, offset = 0).second shouldBe 2L
    }

    @Test
    fun `per-set breakdown round-trips - dry-run preview equals the persisted history (#110)`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        playedMatch(admin = "root", winner = p1.id, loser = p2.id)

        // The dry-run preview carries the v2 per-set steps with net fields null.
        val previewSets =
            calc
                .calculate(token = token(uid = "root"), dryRun = true)
                .shouldBeRight()
                .matches
                .single()
                .changes
                .first { it.userId == p1.id }
                .breakdown
                .sets
        previewSets.size shouldBe 2

        // Committing persists them; reading the history back returns the same per-set breakdown.
        calc.calculate(token = token(uid = "root"), dryRun = false).shouldBeRight()
        val persistedSets = ratings.historyByUser(userId = p1.id).single().setBreakdown
        persistedSets shouldBe previewSets
    }

    @Test
    fun `matches are chained chronologically, carrying ratings forward`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        playedMatch(admin = "root", winner = p1.id, loser = p2.id) // earlier
        playedMatch(admin = "root", winner = p2.id, loser = p1.id) // later

        val outcome = calc.calculate(token = token(uid = "root"), dryRun = true).shouldBeRight()

        outcome.matches.size shouldBe 2
        val firstP1 = outcome.matches[0].changes.first { it.userId == p1.id }
        val secondP1 = outcome.matches[1].changes.first { it.userId == p1.id }
        // the second match starts from where the first left off
        secondP1.previousRating shouldBe firstP1.newRating
    }

    @Test
    fun `only an administrator can trigger the calculation`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provisionUser(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))

        calc.calculate(token = token(uid = "host"), dryRun = true).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
        calc.calculate(token = token(uid = "ghost"), dryRun = false).shouldBeLeft().shouldBeInstanceOf<ServiceError.Forbidden>()
    }

    /** Create + complete a doubles fixture where [team1] beats [team2]; returns the match id. */
    private fun playedDoubles(
        admin: String,
        team1: List<UUID>,
        team2: List<UUID>,
        format: TeamType = TeamType.DOUBLES,
    ): UUID {
        val match =
            matchService.createFixture(
                token = token(uid = admin),
                request =
                    FixtureInput(
                        matchFormat = format,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = LocalDate.parse("2026-01-01"),
                        team1 = team1,
                        team2 = team2,
                    ),
            ).shouldBeRight()
        matchService.uploadResult(
            token = token(uid = admin),
            matchId = match.id,
            request =
                MatchResultRequest(
                    sets =
                        listOf(
                            SetScoreRequest(team1Games = 6, team2Games = 4),
                            SetScoreRequest(team1Games = 6, team2Games = 2),
                        ),
                ),
        ).shouldBeRight()
        return match.id
    }

    @Test
    fun `a doubles match calculates and commits a change for all four players`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val a1 = provisionUser(uid = "a1", rated = true)
        val a2 = provisionUser(uid = "a2", rated = true)
        val b1 = provisionUser(uid = "b1", rated = true)
        val b2 = provisionUser(uid = "b2", rated = true)
        val matchId = playedDoubles(admin = "root", team1 = listOf(a1.id, a2.id), team2 = listOf(b1.id, b2.id))

        val dry = calc.calculate(token = token(uid = "root"), dryRun = true).shouldBeRight()
        val changes = dry.matches.single().changes
        changes.size shouldBe 4
        // Equal 4.0 partners: winners gain, losers lose; each carries a per-set breakdown.
        changes.first { it.userId == a1.id }.let { (it.newRating > it.previousRating).shouldBeTrue() }
        changes.first { it.userId == b1.id }.let { (it.newRating < it.previousRating).shouldBeTrue() }
        changes.forEach { it.breakdown.sets.size shouldBe 2 }

        calc.calculate(token = token(uid = "root"), dryRun = false).shouldBeRight()
        ratings.historyByUser(userId = a1.id).single().matchId shouldBe matchId
        ratings.historyByUser(userId = b2.id).single().matchId shouldBe matchId
        matchRepo.listPendingCalculation().shouldBe(expected = emptyList())
    }

    @Test
    fun `doubles splits the team change by each partner's rating share of the team mean`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        // Winning team: 5.0 & 3.0 (mean 4.0); losing team: 4.0 & 4.0 (mean 4.0). Equal means keep the
        // team change identical to the equal-partner case, but the stronger partner absorbs a larger share.
        val a1 =
            provisionUser(uid = "a1", rated = true).also {
                ratings.setRating(userId = it.id, rating = BigDecimal("5.0"), level = "5.0")
            }
        val a2 =
            provisionUser(uid = "a2", rated = true).also {
                ratings.setRating(userId = it.id, rating = BigDecimal("3.0"), level = "3.0")
            }
        val b1 = provisionUser(uid = "b1", rated = true)
        val b2 = provisionUser(uid = "b2", rated = true)
        playedDoubles(admin = "root", team1 = listOf(a1.id, a2.id), team2 = listOf(b1.id, b2.id))

        val changes = calc.calculate(token = token(uid = "root"), dryRun = true).shouldBeRight().matches.single().changes
        val stronger = changes.first { it.userId == a1.id }.let { it.newRating - it.previousRating }
        val weaker = changes.first { it.userId == a2.id }.let { it.newRating - it.previousRating }

        // Ratio split: 5.0-partner gains more than the 3.0-partner (δ ∝ rᵢ / mean).
        (stronger > weaker).shouldBeTrue()
        // Conservation across the four players.
        changes.sumOf { it.newRating - it.previousRating }.toDouble() shouldBe (0.0 plusOrMinus 0.000001)
    }

    @Test
    fun `a calculator that omits a player's change is rejected`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        playedMatch(admin = "root", winner = p1.id, loser = p2.id)

        // A calculator that returns no rating changes simulates the defensive guard for a player
        // missing from the calculator's response.
        val emptyCalculator =
            object : RankingCalculator {
                override fun calculate(request: RankingCalculationRequest): RankingCalculationResult =
                    RankingCalculationResult(
                        response =
                            RankingCalculationResponse(
                                ratingChanges = emptyMap(),
                                players = emptyMap(),
                                teams = emptyMap(),
                            ),
                        audit = emptyList(),
                    )
            }
        val calcWithEmpty =
            RatingCalculationService(matches = matchRepo, ratings = ratings, users = users, calculator = emptyCalculator)

        calcWithEmpty.calculate(token = token(uid = "root"), dryRun = true).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `a calculator that omits a player's breakdown is rejected`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        playedMatch(admin = "root", winner = p1.id, loser = p2.id)

        // Real rating changes but an empty audit trail → no per-player breakdown → the defensive guard fires.
        val noBreakdownCalculator =
            object : RankingCalculator {
                override fun calculate(request: RankingCalculationRequest): RankingCalculationResult =
                    PerformanceBasedRankingCalculatorImpl().calculate(request = request).copy(audit = emptyList())
            }
        val calcWithoutBreakdown =
            RatingCalculationService(matches = matchRepo, ratings = ratings, users = users, calculator = noBreakdownCalculator)

        calcWithoutBreakdown
            .calculate(token = token(uid = "root"), dryRun = true)
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `calculation fails if a pending match participant has no rating`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        playedMatch(admin = "root", winner = p1.id, loser = p2.id)

        // Strip ratings out from under the pending match (a state the fixture flow prevents,
        // but the calculation guards against): currentRating then has nothing to resolve.
        transaction { UserRatingsTable.deleteAll() }

        calc.calculate(token = token(uid = "root"), dryRun = true).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
    }

    private val eventRepo = EventRepository()

    /**
     * Create a finalized event named [name] over [startDate]..[endDate] holding one completed fixture
     * ([a] beats [b], dated at [startDate]); returns the event id. Finalized so its match is queue-eligible.
     */
    private fun finalizedEventWithMatch(
        name: String,
        startDate: LocalDate,
        endDate: LocalDate,
        a: UUID,
        b: UUID,
    ): UUID {
        val root = users.findByFirebaseUid(firebaseUid = "root")!!.id
        val event =
            eventRepo.create(
                command =
                    CreateEventCommand(
                        name = name,
                        startDate = startDate,
                        endDate = endDate,
                        participantIds = listOf(a, b),
                        createdBy = root,
                    ),
            )
        val fixture =
            matchService.createFixture(
                token = token(uid = "root"),
                request =
                    FixtureInput(
                        matchFormat = TeamType.SINGLES,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = startDate,
                        team1 = listOf(element = a),
                        team2 = listOf(element = b),
                        eventId = event.id,
                    ),
            ).shouldBeRight()
        recordResult(admin = "root", matchId = fixture.id)
        eventRepo.finalize(id = event.id, finalizedAt = LocalDateTime.now(), finalizedBy = root)
        return event.id
    }

    @Test
    fun `no selection processes every pending event, unchanged behaviour (#479)`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val p3 = provisionUser(uid = "p3", rated = true)
        val p4 = provisionUser(uid = "p4", rated = true)
        finalizedEventWithMatch(
            name = "A",
            startDate = LocalDate.parse("2026-01-01"),
            endDate = LocalDate.parse("2026-01-01"),
            a = p1.id,
            b = p2.id,
        )
        finalizedEventWithMatch(
            name = "B",
            startDate = LocalDate.parse("2026-02-01"),
            endDate = LocalDate.parse("2026-02-01"),
            a = p3.id,
            b = p4.id,
        )

        val all = calc.calculate(token = token(uid = "root"), dryRun = true, eventIds = null).shouldBeRight()
        all.matches.size shouldBe 2
        // An empty selection is also the all-pending default.
        calc.calculate(token = token(uid = "root"), dryRun = true, eventIds = emptyList()).shouldBeRight().matches.size shouldBe 2
    }

    @Test
    fun `a selection matching no pending event is a no-op empty run (#479)`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        finalizedEventWithMatch(
            name = "A",
            startDate = LocalDate.parse("2026-01-01"),
            endDate = LocalDate.parse("2026-01-01"),
            a = p1.id,
            b = p2.id,
        )

        // An eventId that matches nothing in the pending timeline scopes to an empty run.
        calc
            .calculate(token = token(uid = "root"), dryRun = true, eventIds = listOf(element = UUID.randomUUID().toString()))
            .shouldBeRight()
            .matches
            .shouldBe(expected = emptyList())
    }

    @Test
    fun `a valid prefix selection previews only those events and matches a full run for them (#479)`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val p3 = provisionUser(uid = "p3", rated = true)
        val p4 = provisionUser(uid = "p4", rated = true)
        val eventA =
            finalizedEventWithMatch(
                name = "A",
                startDate = LocalDate.parse("2026-01-01"),
                endDate = LocalDate.parse("2026-01-01"),
                a = p1.id,
                b = p2.id,
            )
        finalizedEventWithMatch(
            name = "B",
            startDate = LocalDate.parse("2026-02-01"),
            endDate = LocalDate.parse("2026-02-01"),
            a = p3.id,
            b = p4.id,
        )

        val fullFirst = calc.calculate(token = token(uid = "root"), dryRun = true, eventIds = null).shouldBeRight().matches.first()
        // Selecting only the earliest event (A) previews just its one match.
        val scoped =
            calc.calculate(
                token = token(uid = "root"),
                dryRun = true,
                eventIds = listOf(element = eventA.toString()),
            ).shouldBeRight()
        scoped.matches.size shouldBe 1
        scoped.matches.single().matchId shouldBe fullFirst.matchId
        // The scoped ratings are identical to what a full run would produce for that same leading match.
        scoped.matches.single().changes.map { it.userId to it.newRating }.toSet() shouldBe
            fullFirst.changes.map { it.userId to it.newRating }.toSet()
    }

    @Test
    fun `selecting a later event while an earlier one is excluded is rejected, naming the excluded event (#479)`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val p3 = provisionUser(uid = "p3", rated = true)
        val p4 = provisionUser(uid = "p4", rated = true)
        val eventA =
            finalizedEventWithMatch(
                name = "A",
                startDate = LocalDate.parse("2026-01-01"),
                endDate = LocalDate.parse("2026-01-01"),
                a = p1.id,
                b = p2.id,
            )
        val eventB =
            finalizedEventWithMatch(
                name = "B",
                startDate = LocalDate.parse("2026-02-01"),
                endDate = LocalDate.parse("2026-02-01"),
                a = p3.id,
                b = p4.id,
            )

        // Selecting only the LATER event B skips the earlier pending event A → rejected, naming A.
        val error =
            calc
                .calculate(token = token(uid = "root"), dryRun = true, eventIds = listOf(element = eventB.toString()))
                .shouldBeLeft()
                .shouldBeInstanceOf<ServiceError.Validation>()
        error.message stringShouldContain eventA.toString()
        // The same guard applies to commit, not just preview.
        calc
            .calculate(token = token(uid = "root"), dryRun = false, eventIds = listOf(element = eventB.toString()))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()
    }

    @Test
    fun `commit persists only the selected prefix and leaves later pending events unrated (#479)`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser(uid = "p1", rated = true)
        val p2 = provisionUser(uid = "p2", rated = true)
        val p3 = provisionUser(uid = "p3", rated = true)
        val p4 = provisionUser(uid = "p4", rated = true)
        val eventA =
            finalizedEventWithMatch(
                name = "A",
                startDate = LocalDate.parse("2026-01-01"),
                endDate = LocalDate.parse("2026-01-01"),
                a = p1.id,
                b = p2.id,
            )
        finalizedEventWithMatch(
            name = "B",
            startDate = LocalDate.parse("2026-02-01"),
            endDate = LocalDate.parse("2026-02-01"),
            a = p3.id,
            b = p4.id,
        )

        calc.calculate(token = token(uid = "root"), dryRun = false, eventIds = listOf(element = eventA.toString())).shouldBeRight()

        // Event A's players are rated; event B's are untouched and B's match is still pending.
        ratings.historyByUser(userId = p1.id).size shouldBe 1
        ratings.historyByUser(userId = p3.id).shouldBe(expected = emptyList())
        matchRepo.listPendingCalculation().size shouldBe 1
    }
}
