// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.rating

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.dto.match.CreateFixtureRequest
import org.skopeo.dto.match.MatchResultRequest
import org.skopeo.dto.match.SetScoreRequest
import org.skopeo.model.AuthProvider
import org.skopeo.model.Capability
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.User
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRatingsTable
import org.skopeo.repository.UserRepository
import org.skopeo.service.match.MatchService
import org.skopeo.service.user.ForbiddenException
import org.skopeo.service.user.VerifiedFirebaseToken
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
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
        if (rated) ratings.setRating(user.id, BigDecimal("4.0"), "4.0", BigDecimal("0.50"))
        return user
    }

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid)

    /** Create + complete a fixture where [winner] beats [loser]; returns the match id. */
    private fun playedMatch(
        admin: String,
        winner: UUID,
        loser: UUID,
    ): UUID {
        val match =
            matchService.createFixture(
                token(admin),
                CreateFixtureRequest(
                    matchType = "SINGLES",
                    matchFormat = "BEST_OF_THREE",
                    matchDate = "2026-01-01",
                    team1 = listOf(winner.toString()),
                    team2 = listOf(loser.toString()),
                ),
            )
        matchService.uploadResult(token(admin), match.id, MatchResultRequest(listOf(SetScoreRequest(6, 4), SetScoreRequest(6, 2))))
        return match.id
    }

    @Test
    fun `dry-run previews changes without writing`() {
        provisionUser("root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser("p1", rated = true)
        val p2 = provisionUser("p2", rated = true)
        val matchId = playedMatch("root", winner = p1.id, loser = p2.id)

        val dry = calc.calculate(token("root"), dryRun = true)

        dry.dryRun.shouldBeTrue()
        dry.matches.single().changes.size shouldBe 2
        dry.matches.single().changes.first { it.userId == p1.id }.let {
            it.previousRating shouldBe BigDecimal("4.000000")
            (it.newRating > BigDecimal("4.000000")).shouldBeTrue() // winner gains
        }
        // nothing persisted
        ratings.findCurrentRating(p1.id)!!.currentRating shouldBe BigDecimal("4.000000")
        matchRepo.findById(matchId)!!.ratedAt.shouldBeNull()
        matchRepo.listPendingCalculation().size shouldBe 1
    }

    @Test
    fun `commit persists ratings, history, and marks matches rated`() {
        provisionUser("root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser("p1", rated = true)
        val p2 = provisionUser("p2", rated = true)
        val matchId = playedMatch("root", winner = p1.id, loser = p2.id)

        val committed = calc.calculate(token("root"), dryRun = false)
        committed.dryRun.shouldBeFalse()

        ratings.findCurrentRating(p1.id)!!.let {
            (it.currentRating > BigDecimal("4.000000")).shouldBeTrue()
            it.matchesPlayed shouldBe 1
        }
        ratings.historyByUser(p1.id).single().matchId shouldBe matchId
        matchRepo.findById(matchId)!!.ratedAt.shouldNotBeNull()
        matchRepo.listPendingCalculation().shouldBe(emptyList())

        // idempotent — nothing left to process
        calc.calculate(token("root"), dryRun = false).matches.shouldBe(emptyList())
    }

    @Test
    fun `matches are chained chronologically, carrying ratings forward`() {
        provisionUser("root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser("p1", rated = true)
        val p2 = provisionUser("p2", rated = true)
        playedMatch("root", winner = p1.id, loser = p2.id) // earlier
        playedMatch("root", winner = p2.id, loser = p1.id) // later

        val outcome = calc.calculate(token("root"), dryRun = true)

        outcome.matches.size shouldBe 2
        val firstP1 = outcome.matches[0].changes.first { it.userId == p1.id }
        val secondP1 = outcome.matches[1].changes.first { it.userId == p1.id }
        // the second match starts from where the first left off
        secondP1.previousRating shouldBe firstP1.newRating
    }

    @Test
    fun `only an administrator can trigger the calculation`() {
        provisionUser("root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        provisionUser("host", roles = setOf(Capability.PLAYER, Capability.HOST))

        shouldThrow<ForbiddenException> { calc.calculate(token("host"), dryRun = true) }
        shouldThrow<ForbiddenException> { calc.calculate(token("ghost"), dryRun = false) }
    }

    @Test
    fun `calculation fails if a pending match participant has no rating`() {
        provisionUser("root", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provisionUser("p1", rated = true)
        val p2 = provisionUser("p2", rated = true)
        playedMatch("root", winner = p1.id, loser = p2.id)

        // Strip ratings out from under the pending match (a state the fixture flow prevents,
        // but the calculation guards against): currentRating then has nothing to resolve.
        transaction { UserRatingsTable.deleteAll() }

        shouldThrow<IllegalArgumentException> { calc.calculate(token("root"), dryRun = true) }
    }
}
