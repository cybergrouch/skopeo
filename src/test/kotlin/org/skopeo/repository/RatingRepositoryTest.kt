// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.model.AuthProvider
import org.skopeo.model.CalculationBreakdownSnapshot
import org.skopeo.model.CreateFixtureCommand
import org.skopeo.model.MatchSetResult
import org.skopeo.model.MatchType
import org.skopeo.model.NameType
import org.skopeo.model.ProvisionUserCommand
import org.skopeo.model.RatingHistoryWrite
import org.skopeo.model.SetCalculationBreakdown
import org.skopeo.model.TeamType
import org.skopeo.model.UserIdentity
import org.skopeo.model.UserName
import org.skopeo.testsupport.PostgresTestDatabase
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class RatingRepositoryTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val matches = MatchRepository()
    private val ratings = RatingRepository()

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
                    names = listOf(UserName(type = NameType.DISPLAY, value = uid)),
                ),
        ).id

    /** Create a COMPLETED match on [matchDate] between [u1] and [u2] of the given [matchType]. */
    private fun completedMatch(
        u1: UUID,
        u2: UUID,
        matchType: MatchType,
        matchDate: LocalDate,
    ) {
        val match =
            matches.createFixture(
                command =
                    CreateFixtureCommand(
                        matchFormat = TeamType.SINGLES,
                        matchType = matchType,
                        matchDate = matchDate,
                        team1UserIds = listOf(element = u1),
                        team2UserIds = listOf(element = u2),
                        team1Name = "T1",
                        team2Name = "T2",
                        createdBy = u1,
                    ),
            )
        matches.addResult(
            matchId = match.id,
            sets = listOf(element = MatchSetResult(setNumber = 1, team1Games = 6, team2Games = 0, winnerTeamId = match.team1.teamId)),
            winnerTeamId = match.team1.teamId,
            recordedBy = u1,
            completedAt = LocalDateTime.now(),
        )
    }

    @Test
    fun `setRating inserts then updates, per system`() {
        val userId = newUser(uid = "u1")

        ratings.setRating(userId = userId, rating = BigDecimal("3.5"), level = "3.5")
        ratings.findCurrentRating(userId = userId)!!.let {
            it.currentRating shouldBe BigDecimal("3.500000")
            it.currentLevel shouldBe "3.5"
            it.matchesPlayed shouldBe 0
        }

        ratings.setRating(userId = userId, rating = BigDecimal("4.0"), level = "4.0")
        ratings.findCurrentRating(userId = userId)!!.currentRating shouldBe BigDecimal("4.000000")
        ratings.findByUser(userId = userId).size shouldBe 1
    }

    @Test
    fun `confidence is computed (#459) from windowed match sparsity, not from the current rating source`() {
        val userId = newUser(uid = "conf")
        val opponent = newUser(uid = "opp")

        // No matches yet → confidence 0, whatever the rating source (a bare admin override here).
        ratings.setRating(userId = userId, rating = BigDecimal("4.0"), level = "4.0")
        ratings.findCurrentRating(userId = userId)!!.confidence.compareTo(other = BigDecimal.ZERO) shouldBe 0

        // Two in-window open-play matches ⇒ weighted count 1.0, gap 30 → ≈ 0.595 (worked example A).
        val today = LocalDate.now()
        repeat(times = 2) { completedMatch(u1 = userId, u2 = opponent, matchType = MatchType.OPEN_PLAY, matchDate = today) }
        ratings.findCurrentRating(userId = userId)!!.confidence.toDouble() shouldBe (0.595 plusOrMinus 0.001)

        // A match outside the 30-day window does not count — confidence is unchanged.
        completedMatch(u1 = userId, u2 = opponent, matchType = MatchType.OPEN_PLAY, matchDate = today.minusDays(40))
        ratings.findCurrentRating(userId = userId)!!.confidence.toDouble() shouldBe (0.595 plusOrMinus 0.001)
    }

    @Test
    fun `findByUserAndSystem is null when absent`() {
        ratings.findCurrentRating(userId = UUID.randomUUID()).shouldBeNull()
    }

    @Test
    fun `history is empty for a new user (both filters)`() {
        val userId = newUser(uid = "u2")
        ratings.historyByUser(userId = userId) shouldBe emptyList()
    }

    @Test
    fun `findCurrentRatings returns a map keyed by user, omitting the unrated and the empty input`() {
        val rated = newUser(uid = "rated")
        val unrated = newUser(uid = "unrated")
        ratings.setRating(userId = rated, rating = BigDecimal("4.5"), level = "4.5")

        val map = ratings.findCurrentRatings(userIds = listOf(rated, unrated))
        map.keys shouldBe setOf(element = rated)
        map[rated]?.currentLevel shouldBe "4.5"

        ratings.findCurrentRatings(userIds = emptyList()) shouldBe emptyMap()
    }

    @Test
    fun `pending assessment lists active users without a rating, with a total`() {
        val unrated = newUser(uid = "unrated")
        val rated = newUser(uid = "rated")
        ratings.setRating(userId = rated, rating = BigDecimal("3.0"), level = "3.0")

        val (ids, total) = ratings.userIdsPendingAssessment(limit = 50, offset = 0)
        ids shouldContain unrated
        ids shouldNotContain rated
        total shouldBe 1L
    }

    @Test
    fun `pending assessment paginates by limit and offset into disjoint, complete pages`() {
        val all = (1..5).map { newUser(uid = "p$it") }.toSet()

        val (page1, total) = ratings.userIdsPendingAssessment(limit = 2, offset = 0)
        val (page2, _) = ratings.userIdsPendingAssessment(limit = 2, offset = 2)
        val (page3, _) = ratings.userIdsPendingAssessment(limit = 2, offset = 4)

        total shouldBe 5L
        page1 shouldHaveSize 2
        page2 shouldHaveSize 2
        page3 shouldHaveSize 1
        // Pages are disjoint and together cover every pending user (stable order, no gaps/dupes).
        (page1 + page2 + page3).toSet() shouldBe all
    }

    private fun historyWrite(
        userId: UUID,
        newRating: String,
        breakdown: CalculationBreakdownSnapshot?,
        completedAt: LocalDateTime? = null,
        calculatedAt: LocalDateTime = LocalDateTime.now(),
        ratingRunId: UUID? = null,
    ) = RatingHistoryWrite(
        userId = userId,
        matchId = null,
        previousRating = BigDecimal("4.0"),
        newRating = BigDecimal(newRating),
        ratingChange = BigDecimal(newRating).subtract(BigDecimal("4.0")),
        percentChange = null,
        previousLevel = "4.0",
        newLevel = "4.0",
        levelChanged = false,
        breakdown = breakdown,
        completedAt = completedAt,
        calculatedAt = calculatedAt,
        ratingRunId = ratingRunId,
    )

    @Test
    fun `appendHistory round-trips the per-set breakdown only when present (#110)`() {
        val userId = newUser(uid = "history")
        val step =
            SetCalculationBreakdown(
                setIndex = 0,
                score = "6-2",
                dominance = "0.500000",
                scale = "0.400000",
                ratingGap = "0.500000",
                normalizedGap = "0.083333",
                competitiveThresholdPct = "0.083000",
                isUpset = false,
                upsetMultiplier = "2.000000",
                kFactor = "0.160000",
                delta = "0.032000",
                ratingAfter = "4.032000",
            )
        val netBreakdown =
            CalculationBreakdownSnapshot(
                dominance = BigDecimal("0.200000"),
                scale = BigDecimal("1.000000"),
                ratingGap = BigDecimal("0.500000"),
                normalizedGap = BigDecimal("0.083333"),
                competitiveThresholdPct = BigDecimal("0.083000"),
                isUpset = false,
                upsetMultiplier = BigDecimal("2.000000"),
                kFactor = BigDecimal("0.160000"),
                sets = emptyList(),
            )

        // A null breakdown (admin re-rate), a v1 net breakdown (empty sets), and a v2 per-set breakdown.
        ratings.appendHistory(write = historyWrite(userId = userId, newRating = "4.1", breakdown = null))
        ratings.appendHistory(write = historyWrite(userId = userId, newRating = "4.2", breakdown = netBreakdown))
        ratings.appendHistory(
            write = historyWrite(userId = userId, newRating = "4.3", breakdown = netBreakdown.copy(sets = listOf(element = step))),
        )

        val byRating = ratings.historyByUser(userId = userId).associateBy { it.newRating }
        byRating.getValue(key = BigDecimal("4.100000")).setBreakdown shouldBe emptyList()
        byRating.getValue(key = BigDecimal("4.200000")).setBreakdown shouldBe emptyList()
        byRating.getValue(key = BigDecimal("4.300000")).setBreakdown shouldBe listOf(element = step)
    }

    /** The persisted rating_run_id per row, keyed by its new_rating (unique in these tests). */
    private fun runIdsByNewRating(userId: UUID): Map<BigDecimal, UUID?> =
        transaction {
            UserRatingHistoryTable
                .selectAll()
                .where { UserRatingHistoryTable.userId eq userId }
                .associate { it[UserRatingHistoryTable.newRating] to it[UserRatingHistoryTable.ratingRunId] }
        }

    @Test
    fun `appendHistory persists the rating_run_id, shared within a run and null for match-less rows (#481)`() {
        val userId = newUser(uid = "runid")
        val runId = UUID.randomUUID()

        // Two rows written in one calc run share the run's id; a match-less admin row carries none.
        ratings.appendHistory(write = historyWrite(userId = userId, newRating = "4.1", breakdown = null, ratingRunId = runId))
        ratings.appendHistory(write = historyWrite(userId = userId, newRating = "4.2", breakdown = null, ratingRunId = runId))
        ratings.appendHistory(write = historyWrite(userId = userId, newRating = "4.9", breakdown = null, ratingRunId = null))

        val byRating = runIdsByNewRating(userId = userId)
        byRating.getValue(key = BigDecimal("4.100000")) shouldBe runId
        byRating.getValue(key = BigDecimal("4.200000")) shouldBe runId
        byRating.getValue(key = BigDecimal("4.900000")).shouldBeNull()
    }

    @Test
    fun `a second calc run gets a distinct rating_run_id from the first (#481)`() {
        val userId = newUser(uid = "tworuns")
        val run1 = UUID.randomUUID()
        val run2 = UUID.randomUUID()

        ratings.appendHistory(write = historyWrite(userId = userId, newRating = "4.1", breakdown = null, ratingRunId = run1))
        ratings.appendHistory(write = historyWrite(userId = userId, newRating = "4.2", breakdown = null, ratingRunId = run2))

        val byRating = runIdsByNewRating(userId = userId)
        val first = byRating.getValue(key = BigDecimal("4.100000"))
        val second = byRating.getValue(key = BigDecimal("4.200000"))
        first shouldBe run1
        second shouldBe run2
        (first == second) shouldBe false
    }

    @Test
    fun `history is newest-first, breaking ties within a batch by completed_at, match-less rows last (#301)`() {
        val userId = newUser(uid = "order")
        val batch1 = LocalDateTime.of(2026, 6, 1, 12, 0, 0)
        val batch2 = LocalDateTime.of(2026, 6, 2, 12, 0, 0)

        // One calc batch stamps every row with the SAME calculatedAt (batch1); completed_at is the only
        // discriminator. The match-less row (null completed_at) must sort last, i.e. earliest.
        ratings.appendHistory(
            write =
                historyWrite(
                    userId = userId,
                    newRating = "4.1",
                    breakdown = null,
                    completedAt = LocalDateTime.of(2026, 5, 10, 9, 0),
                    calculatedAt = batch1,
                ),
        )
        ratings.appendHistory(
            write =
                historyWrite(
                    userId = userId,
                    newRating = "4.2",
                    breakdown = null,
                    completedAt = LocalDateTime.of(2026, 5, 20, 9, 0),
                    calculatedAt = batch1,
                ),
        )
        ratings.appendHistory(
            write = historyWrite(userId = userId, newRating = "4.0", breakdown = null, completedAt = null, calculatedAt = batch1),
        )
        // A later batch leads regardless of its (earlier) completed_at — calculatedAt dominates.
        ratings.appendHistory(
            write =
                historyWrite(
                    userId = userId,
                    newRating = "4.5",
                    breakdown = null,
                    completedAt = LocalDateTime.of(2026, 1, 1, 9, 0),
                    calculatedAt = batch2,
                ),
        )

        // Newest batch first (calculatedAt DESC); within batch1, completed_at DESC; the null-completed
        // (match-less) row sorts last.
        ratings.historyByUser(userId = userId).map { it.newRating } shouldBe
            listOf(
                BigDecimal("4.500000"),
                BigDecimal("4.200000"),
                BigDecimal("4.100000"),
                BigDecimal("4.000000"),
            )
    }
}
