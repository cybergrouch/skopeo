// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skopeo.common.redaction.asRedactable
import org.skopeo.domain.mapper.entity.event.toDomain
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuthProvider
import org.skopeo.domain.model.CreateEventCommand
import org.skopeo.domain.model.CreateFixtureCommand
import org.skopeo.domain.model.MatchSetResult
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.NameType
import org.skopeo.domain.model.ProvisionUserCommand
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.model.UserIdentity
import org.skopeo.domain.model.UserName
import org.skopeo.testsupport.PostgresTestDatabase
import org.skopeo.testsupport.seedClub
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The stored calibration count (#1051) — `user_ratings.calibration_matches_rated`.
 *
 * A denormalisation is only as good as its maintenance, and a drifted counter is invisible: nothing looks
 * wrong, a player is just silently stuck in or out of calibration, which decides whether their opponents'
 * ratings move at all. So almost every test here ends in the same assertion — **the stored count equals
 * what `MatchRepository.countRatedMatchesSince` computes from the match tables right now**. That is the
 * reconciliation the issue asks for, and it is the only assertion that can catch drift, because the
 * stored value is otherwise self-consistent whatever it says.
 *
 * The cases are chosen from the paths that change *which matches count as rated*: a match becoming rated,
 * an event-scoped reversal (#478), a match or event soft-delete, a participation merge, a score
 * correction (#911) and a fresh designation. Un-finalize (#477) is absent on purpose — it refuses while
 * any of the event's matches are rated, so it cannot move this count.
 */
class CalibrationCountsTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun connect() {
            PostgresTestDatabase.start()
        }
    }

    private val users = UserRepository()
    private val matches = MatchRepository()
    private val events = EventRepository()
    private val ratings = RatingRepository()

    @BeforeEach
    fun reset() {
        PostgresTestDatabase.truncate()
    }

    private fun newUser(uid: String): UUID =
        users
            .provision(
                command =
                    ProvisionUserCommand(
                        firebaseUid = uid.asRedactable(),
                        identity = UserIdentity(provider = AuthProvider.PASSWORD, providerUid = uid, isPrimary = true),
                        names = listOf(element = UserName(type = NameType.DISPLAY, value = uid)),
                    ),
            ).toDomain()
            .id

    /** A manual designation — the only thing that opens a window, and it restarts the count at 0. */
    private fun designate(
        userId: UUID,
        value: String = "4.0",
    ) = ratings.setRating(userId = userId, rating = BigDecimal(value), level = value)

    private fun event(
        creator: UUID,
        members: List<UUID>,
    ): UUID =
        events
            .create(
                command =
                    CreateEventCommand(
                        clubId = seedClub().id,
                        name = "E-${UUID.randomUUID()}",
                        startDate = LocalDate.of(2026, 1, 1),
                        endDate = LocalDate.of(2026, 1, 3),
                        participantIds = members,
                        createdBy = creator,
                    ),
            ).toDomain()
            .id

    /** A COMPLETED (pending-calculation) singles match under [eventId]. */
    private fun completedMatch(
        u1: UUID,
        u2: UUID,
        eventId: UUID,
    ): UUID {
        val match =
            matches
                .createFixture(
                    command =
                        CreateFixtureCommand(
                            matchFormat = TeamType.SINGLES,
                            matchType = MatchType.OPEN_PLAY,
                            matchDate = LocalDate.of(2026, 1, 2),
                            team1UserIds = listOf(element = u1),
                            team2UserIds = listOf(element = u2),
                            team1Name = "T1",
                            team2Name = "T2",
                            createdBy = u1,
                            eventId = eventId,
                        ),
                ).toDomain()
        matches
            .addResult(
                matchId = match.id,
                sets =
                    listOf(
                        element =
                            MatchSetResult(
                                setNumber = 1,
                                team1Games = 6,
                                team2Games = 4,
                                winnerTeamId = match.team1.teamId,
                            ),
                    ),
                winnerTeamId = match.team1.teamId,
                recordedBy = u1,
                completedAt = LocalDateTime.now(),
            ).shouldBeRight()
        return match.id
    }

    /** Play and rate a match, returning its id. */
    private fun ratedMatch(
        u1: UUID,
        u2: UUID,
        eventId: UUID,
    ): UUID {
        val id = completedMatch(u1 = u1, u2 = u2, eventId = eventId)
        matches.markRated(matchId = id, ratedAt = LocalDateTime.now(), ratedBy = u1)
        return id
    }

    private fun storedCount(userId: UUID): Int = ratings.findCurrentRating(userId = userId).shouldNotBeNull().calibrationMatchesRated

    /**
     * THE guard on the whole denormalisation: the stored count against an independent recomputation from
     * the match tables, for each of [userIds] that has an open window.
     */
    private fun reconcile(userIds: List<UUID>) {
        userIds.forEach { userId ->
            // A player with no rating row and a player with no window have nothing to reconcile: the
            // column is unread for both, because a null stamp answers "not calibrating" on its own.
            val row = ratings.findCurrentRating(userId = userId)
            val startedAt = row?.calibrationStartedAt
            if (row != null && startedAt != null) {
                withClue(clue = "stored count for $userId drifted from the live query") {
                    row.calibrationMatchesRated shouldBe matches.countRatedMatchesSince(userId = userId, since = startedAt)
                }
            }
        }
    }

    @Test
    fun `rating a match advances the stored count, and it agrees with the live query (#1051)`() {
        val player = newUser(uid = "player")
        val opponent = newUser(uid = "opponent")
        val eventId = event(creator = player, members = listOf(player, opponent))
        designate(userId = player)

        withClue(clue = "a fresh designation starts at zero") { storedCount(userId = player) shouldBe 0 }
        ratedMatch(u1 = player, u2 = opponent, eventId = eventId)
        storedCount(userId = player) shouldBe 1
        ratedMatch(u1 = player, u2 = opponent, eventId = eventId)
        storedCount(userId = player) shouldBe 2

        // A completed-but-unrated match must not move it: the calibrating effect is applied when the
        // rating calculation commits, and `rated_at` is that moment.
        completedMatch(u1 = player, u2 = opponent, eventId = eventId)
        storedCount(userId = player) shouldBe 2
        reconcile(userIds = listOf(player, opponent))
    }

    @Test
    fun `a fresh designation restarts the count, so the window restarts with it (#881, #1051)`() {
        val player = newUser(uid = "player")
        val opponent = newUser(uid = "opponent")
        val eventId = event(creator = player, members = listOf(player, opponent))
        designate(userId = player)
        repeat(times = 3) { ratedMatch(u1 = player, u2 = opponent, eventId = eventId) }
        storedCount(userId = player) shouldBe 3

        // A fresh designation is a fresh guess. The matches played under the previous window do not count
        // against the new one, and nothing has been rated since an instant written just now — so 0 is
        // arrived at without a recompute, and the recompute agrees.
        designate(userId = player, value = "3.5")

        storedCount(userId = player) shouldBe 0
        reconcile(userIds = listOf(element = player))
    }

    @Test
    fun `matches rated before the designation never enter the stored count (#1051)`() {
        val player = newUser(uid = "player")
        val opponent = newUser(uid = "opponent")
        val eventId = event(creator = player, members = listOf(player, opponent))
        // A long-standing player with rated history who is then re-assessed gets a FULL window, not an
        // instantly-expired one.
        repeat(times = 2) { ratedMatch(u1 = player, u2 = opponent, eventId = eventId) }

        designate(userId = player)

        storedCount(userId = player) shouldBe 0
        reconcile(userIds = listOf(element = player))
    }

    @Test
    fun `reversing an event's ratings rewinds the count, even for a player with no history row (#478, #1051)`() {
        val player = newUser(uid = "player")
        val opponent = newUser(uid = "opponent")
        val eventId = event(creator = player, members = listOf(player, opponent))
        designate(userId = player)
        repeat(times = 3) { ratedMatch(u1 = player, u2 = opponent, eventId = eventId) }
        storedCount(userId = player) shouldBe 3

        // The player is calibrating, so in production the calculation SUPPRESSED their opponents and wrote
        // no history row for them at all — which is exactly why the affected players are resolved from
        // participation rather than from `user_rating_history`. A history-derived set would have missed the
        // one player whose count actually matters here and left them stuck at the end of a reversed window.
        ratings.historyByUser(userId = player).shouldBeEmpty()

        matches.clearRatedForEvent(eventId = eventId) shouldBe 3

        storedCount(userId = player) shouldBe 0
        reconcile(userIds = listOf(player, opponent))
    }

    @Test
    fun `soft-deleting a rated match takes it off the clock, and restoring it puts it back (#1051)`() {
        val player = newUser(uid = "player")
        val opponent = newUser(uid = "opponent")
        val eventId = event(creator = player, members = listOf(player, opponent))
        designate(userId = player)
        ratedMatch(u1 = player, u2 = opponent, eventId = eventId)
        val deletable = ratedMatch(u1 = player, u2 = opponent, eventId = eventId)
        storedCount(userId = player) shouldBe 2

        // A soft-deleted match counts for nothing else, so it must not advance the clock either.
        matches.setActive(matchId = deletable, active = false, disabledAt = LocalDateTime.now()).shouldBeRight()
        storedCount(userId = player) shouldBe 1
        reconcile(userIds = listOf(player, opponent))

        // Re-enabling is deliberately ungated whatever the match's status, so the count has to come back.
        // A counter that only ever decremented on delete would leave this player short for good.
        matches.setActive(matchId = deletable, active = true, disabledAt = null).shouldBeRight()
        storedCount(userId = player) shouldBe 2
        reconcile(userIds = listOf(player, opponent))
    }

    @Test
    fun `soft-deleting the event hides its rated matches from the clock (#1051)`() {
        val player = newUser(uid = "player")
        val opponent = newUser(uid = "opponent")
        val kept = event(creator = player, members = listOf(player, opponent))
        val doomed = event(creator = player, members = listOf(player, opponent))
        designate(userId = player)
        ratedMatch(u1 = player, u2 = opponent, eventId = kept)
        repeat(times = 2) { ratedMatch(u1 = player, u2 = opponent, eventId = doomed) }
        storedCount(userId = player) shouldBe 3

        // `ClubService.delete` cascades onto every event of the club with no rated-match guard, which is
        // why this is maintained at the repository write site rather than in a caller that could forget.
        events.setActive(id = doomed, active = false, disabledAt = LocalDateTime.now()) shouldBe true

        storedCount(userId = player) shouldBe 1
        reconcile(userIds = listOf(player, opponent))
    }

    @Test
    fun `a score correction leaves the count alone, because a corrected match is still rated (#911, #1051)`() {
        val player = newUser(uid = "player")
        val opponent = newUser(uid = "opponent")
        val eventId = event(creator = player, members = listOf(player, opponent))
        designate(userId = player)
        val corrected = ratedMatch(u1 = player, u2 = opponent, eventId = eventId)
        storedCount(userId = player) shouldBe 1

        // A correction re-records the score and stamps re_rated_at, deliberately leaving `rated_at` set so
        // the match never re-enters the pending-calculation queue. The match therefore still counts, once —
        // an increment-on-rating counter would have double-counted it here.
        val match = matches.findById(matchId = corrected).shouldBeRight().toDomain()
        matches
            .addResult(
                matchId = corrected,
                sets =
                    listOf(
                        element =
                            MatchSetResult(
                                setNumber = 1,
                                team1Games = 4,
                                team2Games = 6,
                                winnerTeamId = match.team2.teamId,
                            ),
                    ),
                winnerTeamId = match.team2.teamId,
                recordedBy = player,
                completedAt = LocalDateTime.now(),
            ).shouldBeRight()
        matches.markReRated(matchId = corrected, reRatedAt = LocalDateTime.now())

        storedCount(userId = player) shouldBe 1
        reconcile(userIds = listOf(player, opponent))
    }

    @Test
    fun `a merge moves the count onto the survivor, consistently with the inherited window (#881, #1051)`() {
        val survivor = newUser(uid = "survivor")
        val retired = newUser(uid = "retired")
        val opponent = newUser(uid = "opponent")
        val eventId = event(creator = opponent, members = listOf(survivor, retired, opponent))
        designate(userId = survivor)
        designate(userId = retired)
        ratedMatch(u1 = survivor, u2 = opponent, eventId = eventId)
        repeat(times = 2) { ratedMatch(u1 = retired, u2 = opponent, eventId = eventId) }

        // The retired account's window is stamped LATER than the survivor's (designated second), so the
        // survivor keeps its own — and the retired account's two rated matches now belong to it.
        users.mergeAccounts(retiredId = retired, survivorId = survivor, transferLogin = false)
        ratings.inheritEarlierCalibrationStart(survivorId = survivor, retiredId = retired)

        withClue(clue = "the survivor answers for the play it absorbed") { storedCount(userId = survivor) shouldBe 3 }
        reconcile(userIds = listOf(survivor, retired, opponent))
    }

    @Test
    fun `inheriting an earlier window recomputes the count against the earlier instant (#881, #1051)`() {
        val survivor = newUser(uid = "survivor")
        val retired = newUser(uid = "retired")
        val opponent = newUser(uid = "opponent")
        val eventId = event(creator = opponent, members = listOf(survivor, retired, opponent))
        // Designate the RETIRED account first so its window is the earlier one, then play the survivor's
        // matches, then designate the survivor — so the survivor's own window starts after those matches
        // and its count is 0 until it inherits.
        designate(userId = retired)
        repeat(times = 2) { ratedMatch(u1 = survivor, u2 = opponent, eventId = eventId) }
        designate(userId = survivor)
        storedCount(userId = survivor) shouldBe 0

        users.mergeAccounts(retiredId = retired, survivorId = survivor, transferLogin = false)
        ratings.inheritEarlierCalibrationStart(survivorId = survivor, retiredId = retired)

        // The window moved earlier, so matches that were outside it are inside it now. Nothing about
        // participation changed in this step — only the instant the count is measured from, which is why
        // inheriting has to recompute rather than trust what the merge left behind.
        storedCount(userId = survivor) shouldBe 2
        reconcile(userIds = listOf(survivor, retired, opponent))
    }

    @Test
    fun `the stored count reconciles with the live query across a mixed run of events and reversals (#1051)`() {
        val players = (1..4).map { newUser(uid = "mixed-$it") }
        val opponent = newUser(uid = "mixed-opponent")
        val eventA = event(creator = opponent, members = players + opponent)
        val eventB = event(creator = opponent, members = players + opponent)
        // Designate three of the four; the fourth stays undesignated, so it must never acquire a count.
        players.take(n = 3).forEach { designate(userId = it) }

        players.forEach { player ->
            ratedMatch(u1 = player, u2 = opponent, eventId = eventA)
            ratedMatch(u1 = player, u2 = opponent, eventId = eventB)
            completedMatch(u1 = player, u2 = opponent, eventId = eventB)
        }
        // Reverse one event, soft-delete one match of the other, and re-designate one player mid-run.
        matches.clearRatedForEvent(eventId = eventA)
        val extra = ratedMatch(u1 = players[0], u2 = opponent, eventId = eventB)
        matches.setActive(matchId = extra, active = false, disabledAt = LocalDateTime.now()).shouldBeRight()
        designate(userId = players[1], value = "5.0")

        reconcile(userIds = players + opponent)
        // Spelled out as well as reconciled: a reconciliation test passes trivially if both sides are
        // wrong in the same way, so at least one expectation has to be written by hand.
        storedCount(userId = players[0]) shouldBe 1
        storedCount(userId = players[1]) shouldBe 0
        storedCount(userId = players[2]) shouldBe 1
        withClue(clue = "a player who was never designated has no rating row at all, so no window either") {
            ratings.findCurrentRating(userId = players[3]).shouldBeNull()
        }
    }
}
