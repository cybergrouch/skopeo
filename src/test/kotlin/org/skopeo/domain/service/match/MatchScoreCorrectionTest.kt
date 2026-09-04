// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.match

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.dto.match.MatchScoreCorrectionRequest
import org.skopeo.common.dto.match.SetScoreRequest
import org.skopeo.common.error.ServiceError
import org.skopeo.common.redaction.asRedactable
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.Match
import org.skopeo.domain.model.MatchSetResult
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.User
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.domain.service.rating.RatingAssembler
import org.skopeo.domain.service.rating.RatingCalculationService
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.repository.AuditRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.UserRatingHistoryTable
import org.skopeo.repository.UserRatingsTable
import org.skopeo.repository.UserRepository
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Score correction of an already-rated match (#776). Every scenario builds REAL rated state through the
 * actual calc-commit path, so the deltas being reversed are the ones the calculator really applied.
 *
 * The load-bearing case is `mid-history`: a corrected match with a later rated match on top. That is
 * precisely where the event-scoped reversal (#478) cannot help, and it is what pins the "no cascade"
 * contract — the later match's rating must not move.
 */
class MatchScoreCorrectionTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val matchRepo = MatchRepository()
    private val ratings = RatingAssembler()
    private val audits = AuditRepository()
    private val calc = RatingCalculationService()
    private val service = MatchScoreCorrectionService()

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
                    firebaseUid = uid.asRedactable(),
                    identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                    names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    capabilities = roles,
                ),
        ).toDomain()

    private fun token(uid: String) = VerifiedFirebaseToken(uid = uid, providerUid = uid.asRedactable())

    private fun rate(
        userId: UUID,
        level: String,
    ) = ratings.setRating(userId = userId, rating = BigDecimal(level), level = level)

    /** Seed a COMPLETED eventless singles fixture on [date] where team1 wins by [team1Games]-[team2Games]. */
    private fun seedCompleted(
        host: User,
        p1: User,
        p2: User,
        date: LocalDate = LocalDate.now().minusDays(10),
        team1Games: Int = 6,
        team2Games: Int = 4,
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
                    ),
            ).toDomain()
        val winner = if (team1Games > team2Games) match.team1.teamId else match.team2.teamId
        matchRepo.addResult(
            matchId = match.id,
            sets =
                listOf(
                    element =
                        MatchSetResult(
                            setNumber = 1,
                            team1Games = team1Games,
                            team2Games = team2Games,
                            winnerTeamId = winner,
                        ),
                ),
            winnerTeamId = winner,
            recordedBy = host.id,
            completedAt = LocalDateTime.now(),
        )
        return matchRepo.findById(matchId = match.id).shouldBeRight().toDomain()
    }

    /** A rated single-match scenario: p1 beat p2 6-4, both starting at 4.0, the match now RATED. */
    private data class Rated(
        val admin: User,
        val host: User,
        val p1: User,
        val p2: User,
        val match: Match,
    )

    private fun ratedScenario(): Rated {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        val match = seedCompleted(host = host, p1 = p1, p2 = p2)
        calc.calculate(token = token(uid = "admin"), dryRun = false).shouldBeRight()
        return Rated(
            admin = admin,
            host = host,
            p1 = p1,
            p2 = p2,
            match = matchRepo.findById(matchId = match.id).shouldBeRight().toDomain(),
        )
    }

    private fun correction(
        team1Games: Int,
        team2Games: Int,
        dryRun: Boolean = true,
    ) = MatchScoreCorrectionRequest(
        sets = listOf(element = SetScoreRequest(team1Games = team1Games, team2Games = team2Games)),
        dryRun = dryRun,
    )

    private fun currentRating(userId: UUID): BigDecimal = ratings.findCurrentRating(userId = userId).shouldNotBeNull().currentRating

    @Test
    fun `a dry run previews the reversal and the new delta but writes nothing (#776)`() {
        val r = ratedScenario()
        val before = currentRating(userId = r.p1.id)

        val preview =
            service
                .correctScore(token = token(uid = "admin"), matchId = r.match.id, request = correction(team1Games = 6, team2Games = 0))
                .shouldBeRight()

        preview.dryRun.shouldBeTrue()
        preview.previousScore shouldBe "6-4"
        preview.newScore shouldBe "6-0"
        preview.impacts shouldHaveSize 2
        // Nothing was persisted: the rating, the score and the re-rated marker are all untouched.
        currentRating(userId = r.p1.id) shouldBe before
        matchRepo.findById(matchId = r.match.id).shouldBeRight().toDomain().let { m ->
            m.reRatedAt.shouldBeNull()
            m.sets.single().team2Games shouldBe 4
        }
    }

    @Test
    fun `the preview arithmetic is current minus the reversed delta plus the new delta (#776)`() {
        val r = ratedScenario()
        val impact =
            service
                .correctScore(token = token(uid = "admin"), matchId = r.match.id, request = correction(team1Games = 6, team2Games = 0))
                .shouldBeRight()
                .impacts
                .single { it.userId == r.p1.id.toString() }

        val current = BigDecimal(impact.currentRating)
        val reversed = BigDecimal(impact.reversedChange)
        val newChange = BigDecimal(impact.newChange)
        // netAdjustment is exactly newChange - reversedChange, and the resulting rating follows from it.
        BigDecimal(impact.netAdjustment) shouldBe newChange.subtract(reversed)
        BigDecimal(impact.resultingRating) shouldBe current.subtract(reversed).add(newChange)
    }

    @Test
    fun `committing a more dominant score moves the winner further up than the original result did (#776)`() {
        val r = ratedScenario()
        val afterFirstRating = currentRating(userId = r.p1.id)

        service
            .correctScore(
                token = token(uid = "admin"),
                matchId = r.match.id,
                request = correction(team1Games = 6, team2Games = 0, dryRun = false),
            ).shouldBeRight()

        // 6-0 is more dominant than 6-4, so the winner ends higher and the loser lower than before.
        (currentRating(userId = r.p1.id) > afterFirstRating).shouldBeTrue()
        (currentRating(userId = r.p2.id) < BigDecimal("4.000000")).shouldBeTrue()
    }

    @Test
    fun `correcting a score back to its original value is a net no-op (#776)`() {
        val r = ratedScenario()
        val p1Before = currentRating(userId = r.p1.id)
        val p2Before = currentRating(userId = r.p2.id)

        service
            .correctScore(
                token = token(uid = "admin"),
                matchId = r.match.id,
                request = correction(team1Games = 6, team2Games = 4, dryRun = false),
            ).shouldBeRight()

        // Reversing the stored delta and re-applying an identically-recomputed one must cancel exactly.
        currentRating(userId = r.p1.id) shouldBe p1Before
        currentRating(userId = r.p2.id) shouldBe p2Before
    }

    @Test
    fun `a correction to a mid-history match leaves the later rated match untouched (#776)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        // Two matches: the earlier one gets corrected, the later one must keep its own rating change.
        val earlier = seedCompleted(host = host, p1 = p1, p2 = p2, date = LocalDate.now().minusDays(20))
        val later = seedCompleted(host = host, p1 = p1, p2 = p2, date = LocalDate.now().minusDays(2))
        calc.calculate(token = token(uid = "admin"), dryRun = false).shouldBeRight()

        val laterRowBefore = ratings.historyForMatches(matchIds = listOf(element = later.id)).single { it.userId == p1.id }

        service
            .correctScore(
                token = token(uid = "admin"),
                matchId = earlier.id,
                request = correction(team1Games = 6, team2Games = 0, dryRun = false),
            ).shouldBeRight()

        // No cascade: the later match's history row is byte-for-byte the same, and it stays rated.
        val laterRowAfter = ratings.historyForMatches(matchIds = listOf(element = later.id)).single { it.userId == p1.id }
        laterRowAfter.id shouldBe laterRowBefore.id
        laterRowAfter.ratingChange shouldBe laterRowBefore.ratingChange
        laterRowAfter.newRating shouldBe laterRowBefore.newRating
        matchRepo.findById(matchId = later.id).shouldBeRight().toDomain().reRatedAt.shouldBeNull()
    }

    @Test
    fun `a commit supersedes the old history row and writes a replacement marked as a correction (#776)`() {
        val r = ratedScenario()
        val originalRowId = ratings.historyForMatches(matchIds = listOf(element = r.match.id)).single { it.userId == r.p1.id }.id

        service
            .correctScore(
                token = token(uid = "admin"),
                matchId = r.match.id,
                request = correction(team1Games = 6, team2Games = 0, dryRun = false),
            ).shouldBeRight()

        val live = ratings.historyForMatches(matchIds = listOf(element = r.match.id))
        // Exactly one live row per player — the old one is soft-deleted, not returned, and not hard-deleted.
        live shouldHaveSize 2
        val replacement = live.single { it.userId == r.p1.id }
        (replacement.id == originalRowId).shouldBeFalse()
        replacement.correctedAt.shouldNotBeNull()
        replacement.netAdjustment.shouldNotBeNull()
        // The replacement records the historical context, not the present-day rating.
        replacement.previousRating shouldBe BigDecimal("4.000000")
    }

    @Test
    fun `a corrected match stays rated, is never re-queued, and carries the re-rated marker (#776)`() {
        val r = ratedScenario()

        service
            .correctScore(
                token = token(uid = "admin"),
                matchId = r.match.id,
                request = correction(team1Games = 6, team2Games = 0, dryRun = false),
            ).shouldBeRight()

        val corrected = matchRepo.findById(matchId = r.match.id).shouldBeRight().toDomain()
        corrected.ratedAt.shouldNotBeNull()
        corrected.reRatedAt.shouldNotBeNull()
        corrected.reRatedCount shouldBe 1
        // Re-queuing would double-apply the delta on the next calculation run.
        matchRepo.listPendingCalculation().map { it.toDomain().id }.shouldBeEmpty()
    }

    @Test
    fun `a correction leaves matches played, last match date and computed confidence untouched (#776)`() {
        val r = ratedScenario()
        val before = ratings.findCurrentRating(userId = r.p1.id).shouldNotBeNull()

        service
            .correctScore(
                token = token(uid = "admin"),
                matchId = r.match.id,
                request = correction(team1Games = 6, team2Games = 0, dryRun = false),
            ).shouldBeRight()

        val after = ratings.findCurrentRating(userId = r.p1.id).shouldNotBeNull()
        // The match was already counted, so a correction must not re-count it.
        after.matchesPlayed shouldBe before.matchesPlayed
        after.lastMatchDate shouldBe before.lastMatchDate
        // Confidence (#459) is computed from windowed match DATES, so a score-only correction cannot move it.
        after.confidence shouldBe before.confidence
    }

    @Test
    fun `a corrected score that flips the winner is applied and reported as a winner change (#776)`() {
        val r = ratedScenario()

        val outcome =
            service
                .correctScore(
                    token = token(uid = "admin"),
                    matchId = r.match.id,
                    request = correction(team1Games = 4, team2Games = 6, dryRun = false),
                ).shouldBeRight()

        outcome.winnerChanged.shouldBeTrue()
        val corrected = matchRepo.findById(matchId = r.match.id).shouldBeRight().toDomain()
        corrected.winnerTeamId shouldBe corrected.team2.teamId
        // The originally-winning player now sits below their 4.0 starting point.
        (currentRating(userId = r.p1.id) < BigDecimal("4.000000")).shouldBeTrue()
    }

    @Test
    fun `a commit records both the score-correction and the re-rating in the Activity Log (#776)`() {
        val r = ratedScenario()

        service
            .correctScore(
                token = token(uid = "admin"),
                matchId = r.match.id,
                request = correction(team1Games = 6, team2Games = 0, dryRun = false),
            ).shouldBeRight()

        val actions = audits.list(actions = null, limit = 200, offset = 0).first.map { it.action }
        actions shouldContain AuditAction.MATCH_SCORE_CORRECTED.name
        actions shouldContain AuditAction.MATCH_RATINGS_RE_RATED.name
    }

    @Test
    fun `a dry run records no Activity Log entry for the correction (#776)`() {
        val r = ratedScenario()

        service
            .correctScore(token = token(uid = "admin"), matchId = r.match.id, request = correction(team1Games = 6, team2Games = 0))
            .shouldBeRight()

        audits
            .list(actions = null, limit = 200, offset = 0)
            .first
            .map { it.action }
            .filter { it == AuditAction.MATCH_SCORE_CORRECTED.name || it == AuditAction.MATCH_RATINGS_RE_RATED.name }
            .shouldBeEmpty()
    }

    @Test
    fun `a non-administrator cannot correct a score (#776)`() {
        val r = ratedScenario()

        service
            .correctScore(token = token(uid = "host"), matchId = r.match.id, request = correction(team1Games = 6, team2Games = 0))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Forbidden>()

        // The host's attempt changed nothing.
        currentRating(userId = r.p1.id).shouldNotBeNull()
        matchRepo.findById(matchId = r.match.id).shouldBeRight().toDomain().reRatedAt.shouldBeNull()
    }

    @Test
    fun `correcting an unrated match is refused and points at the normal edit path (#776)`() {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        // Completed but never rated — MatchService.uploadResult still owns this case.
        val match = seedCompleted(host = host, p1 = p1, p2 = p2)

        val error =
            service
                .correctScore(token = token(uid = "admin"), matchId = match.id, request = correction(team1Games = 6, team2Games = 0))
                .shouldBeLeft()
                .shouldBeInstanceOf<ServiceError.Conflict>()
        error.message shouldContain "not rated yet"
    }

    @Test
    fun `a rated match whose history rows were already reversed cannot be corrected (#776)`() {
        val r = ratedScenario()
        // Simulate a match left rated but with its rating history already superseded: there is then no
        // stored delta to reverse, so the correction must refuse rather than invent one.
        ratings.markMatchHistoryReversed(matchId = r.match.id, reversedAt = LocalDateTime.now())
        val before = currentRating(userId = r.p1.id)

        val error =
            service
                .correctScore(
                    token = token(uid = "admin"),
                    matchId = r.match.id,
                    request = correction(team1Games = 6, team2Games = 0, dryRun = false),
                ).shouldBeLeft()
                .shouldBeInstanceOf<ServiceError.Conflict>()
        error.message shouldContain "no live rating history"

        currentRating(userId = r.p1.id) shouldBe before
        matchRepo.findById(matchId = r.match.id).shouldBeRight().toDomain().reRatedAt.shouldBeNull()
    }

    @Test
    fun `a player left without a current rating row is refused rather than crashing (#776)`() {
        val r = ratedScenario()
        // A rated match always implies both players had a rating, so this is a defensive guard against
        // inconsistent state. Drop the row directly to prove the guard refuses instead of throwing.
        transaction {
            UserRatingsTable.deleteWhere { UserRatingsTable.userId eq r.p1.id }
        }

        val error =
            service
                .correctScore(
                    token = token(uid = "admin"),
                    matchId = r.match.id,
                    request = correction(team1Games = 6, team2Games = 0, dryRun = false),
                ).shouldBeLeft()
                .shouldBeInstanceOf<ServiceError.Conflict>()
        error.message shouldContain "no current rating"

        matchRepo.findById(matchId = r.match.id).shouldBeRight().toDomain().reRatedAt.shouldBeNull()
    }

    @Test
    fun `a corrected score with tied sets is rejected before anything is reversed (#776)`() {
        val r = ratedScenario()
        val before = currentRating(userId = r.p1.id)

        service
            .correctScore(
                token = token(uid = "admin"),
                matchId = r.match.id,
                request =
                    MatchScoreCorrectionRequest(
                        sets =
                            listOf(
                                SetScoreRequest(team1Games = 6, team2Games = 4),
                                SetScoreRequest(team1Games = 4, team2Games = 6),
                            ),
                        dryRun = false,
                    ),
            ).shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Validation>()

        currentRating(userId = r.p1.id) shouldBe before
    }

    /**
     * Make [userId] **settled** by clearing their calibration stamp (#881).
     *
     * Written directly because no live path produces it: every route into `setRating` is a manual
     * designation and so opens a window. This is the state of every player who predates the feature, and
     * the state a player reaches after N rated matches.
     */
    private fun makeSettled(userId: UUID) {
        transaction {
            UserRatingsTable.update(where = { UserRatingsTable.userId eq userId }) {
                it[calibrationStartedAt] = null
            }
        }
    }

    /** A rated scenario where p2 was SETTLED at rating time, so their rating was suppressed (#881). */
    private fun ratedWithSuppressedOpponent(): Rated {
        val host = provision(uid = "host", roles = setOf(Capability.PLAYER, Capability.HOST))
        val admin = provision(uid = "admin", roles = setOf(Capability.PLAYER, Capability.ADMINISTRATOR))
        val p1 = provision(uid = "p1")
        val p2 = provision(uid = "p2")
        rate(userId = p1.id, level = "4.0")
        rate(userId = p2.id, level = "4.0")
        makeSettled(userId = p2.id)
        val match = seedCompleted(host = host, p1 = p1, p2 = p2)
        calc.calculate(token = token(uid = "admin"), dryRun = false).shouldBeRight()
        return Rated(
            admin = admin,
            host = host,
            p1 = p1,
            p2 = p2,
            match = matchRepo.findById(matchId = match.id).shouldBeRight().toDomain(),
        )
    }

    @Test
    fun `a match with a suppressed player is still correctable (#881)`() {
        val r = ratedWithSuppressedOpponent()

        // Before #881 PR 3 this was a 409: the guard demanded a history row for EVERY player, and a
        // suppressed player has none — so any match involving a calibrating player was permanently
        // uncorrectable, reporting that its ratings "cannot be reversed" when there was nothing to reverse.
        val outcome =
            service.correctScore(
                token = token(uid = "admin"),
                matchId = r.match.id,
                request = correction(team1Games = 6, team2Games = 0),
            ).shouldBeRight()

        val forP1 = outcome.impacts.single { it.userId == r.p1.id.toString() }
        val forP2 = outcome.impacts.single { it.userId == r.p2.id.toString() }
        forP1.wasSuppressed shouldBe false
        forP2.wasSuppressed shouldBe true
        // Nothing was applied to p2, so there is nothing to reverse and nothing to re-apply.
        forP2.reversedChange shouldBe "0"
        forP2.newChange shouldBe "0"
        forP2.netAdjustment shouldBe "0"
        forP2.resultingRating shouldBe forP2.currentRating
    }

    @Test
    fun `correcting does not invent a change for a player who never had one (#881)`() {
        val r = ratedWithSuppressedOpponent()
        val p2Before = currentRating(userId = r.p2.id)

        service.correctScore(
            token = token(uid = "admin"),
            matchId = r.match.id,
            request = correction(team1Games = 6, team2Games = 0, dryRun = false),
        ).shouldBeRight()

        // The whole point of reversing what was APPLIED rather than what was computed: p2's rating is
        // untouched, and no replacement history row was written for them.
        currentRating(userId = r.p2.id) shouldBe p2Before
        historyRowsFor(userId = r.p2.id, matchId = r.match.id) shouldBe 0
        // ...while p1's row was superseded and replaced, as usual.
        historyRowsFor(userId = r.p1.id, matchId = r.match.id) shouldBe 1
    }

    @Test
    fun `a player suppressed at rating time stays suppressed even after calibration ends (#881)`() {
        val r = ratedWithSuppressedOpponent()
        val p2Before = currentRating(userId = r.p2.id)
        // p1's calibration window has since closed. The correction must NOT now start moving p2's rating:
        // the suppression belongs to the state as it was when the match was rated, which the absence of a
        // row records. This is the case that would pass a casual review and be wrong in production.
        makeSettled(userId = r.p1.id)

        val outcome =
            service
                .correctScore(
                    token = token(uid = "admin"),
                    matchId = r.match.id,
                    request = correction(team1Games = 6, team2Games = 0, dryRun = false),
                )
                .shouldBeRight()

        outcome.impacts.single { it.userId == r.p2.id.toString() }.wasSuppressed shouldBe true
        currentRating(userId = r.p2.id) shouldBe p2Before
        historyRowsFor(userId = r.p2.id, matchId = r.match.id) shouldBe 0
    }

    @Test
    fun `a rated match with no history for anyone is still a conflict (#881)`() {
        val r = ratedScenario()
        // Suppression can never remove EVERY row — it only ever applies to players who were not
        // calibrating while someone else was. So no rows at all on a rated match is genuine inconsistency,
        // and relaxing the guard must not have relaxed it into silence.
        transaction { UserRatingHistoryTable.deleteAll() }

        service
            .correctScore(token = token(uid = "admin"), matchId = r.match.id, request = correction(team1Games = 6, team2Games = 0))
            .shouldBeLeft()
            .shouldBeInstanceOf<ServiceError.Conflict>()
    }

    private fun historyRowsFor(
        userId: UUID,
        matchId: UUID,
    ): Int =
        transaction {
            UserRatingHistoryTable
                .selectAll()
                .where {
                    (UserRatingHistoryTable.userId eq userId) and
                        (UserRatingHistoryTable.matchId eq matchId) and
                        UserRatingHistoryTable.reversedAt.isNull()
                }.count()
                .toInt()
        }
}
