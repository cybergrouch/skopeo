// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.rating

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.dto.RankingCalculationRequest
import org.skopeo.dto.RankingCalculationResponse
import org.skopeo.dto.match.MatchResultRequest
import org.skopeo.dto.match.SetScoreRequest
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.MatchType
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.ServiceError
import org.skopeo.model.TeamType
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRatingsTable
import org.skopeo.repository.UserRepository
import org.skopeo.service.calculator.RankingCalculationResult
import org.skopeo.service.calculator.RankingCalculator
import org.skopeo.service.calculator.impl.v1.PerformanceBasedRankingCalculatorImpl
import org.skopeo.service.match.FixtureInput
import org.skopeo.service.match.MatchService
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

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
                confidence = BigDecimal("0.50"),
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

    @Test
    fun `a non-SINGLES pending match cannot be calculated`() {
        provisionUser(uid = "root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val a1 = provisionUser(uid = "a1", rated = true)
        val a2 = provisionUser(uid = "a2", rated = true)
        val b1 = provisionUser(uid = "b1", rated = true)
        val b2 = provisionUser(uid = "b2", rated = true)

        val match =
            matchService.createFixture(
                token = token(uid = "root"),
                request =
                    FixtureInput(
                        matchFormat = TeamType.DOUBLES,
                        matchType = MatchType.OPEN_PLAY,
                        matchDate = LocalDate.parse("2026-01-01"),
                        team1 = listOf(a1.id, a2.id),
                        team2 = listOf(b1.id, b2.id),
                    ),
            ).shouldBeRight()
        matchService.uploadResult(
            token = token(uid = "root"),
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

        calc.calculate(token = token(uid = "root"), dryRun = true).shouldBeLeft().shouldBeInstanceOf<ServiceError.Validation>()
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
}
