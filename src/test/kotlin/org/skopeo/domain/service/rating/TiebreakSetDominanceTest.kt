// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.rating

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.skopeo.domain.model.Match
import org.skopeo.domain.model.MatchSetResult
import org.skopeo.domain.model.MatchSide
import org.skopeo.domain.model.MatchStatus
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.TeamType
import org.skopeo.domain.service.calculator.impl.v2.PerformanceBasedRankingCalculatorImpl
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * A tiebreak-decided set must actually move a rating (#1084).
 *
 * This is the consequence half of that bug, stated where it can be seen. `ScoreEngine` banked a
 * tiebreak set at **6-6**, and dominance is the game margin, so the closest possible win arrived at
 * the calculator indistinguishable from a set the two sides shared — and moved nobody. The calculator
 * was never wrong; `ZeroDominanceTest` pins that a genuinely level set *should* move nobody. It was
 * being handed a level set for a set that was won.
 *
 * Both shapes are exercised deliberately: 7-6 is what the engine banks now, and 6-6-with-a-tiebreak is
 * the legacy shape still sitting in any row written before the fix. Keeping the second here records
 * what those rows do rather than leaving it to be rediscovered, and is the evidence behind the
 * backfill decision.
 *
 * At [buildRequest] rather than through the service, for the same reason as
 * [AbandonedTiebreakRatingInputTest]: that is the boundary where stored rows become calculator input,
 * so the case needs no database and no rating pipeline in the way.
 */
class TiebreakSetDominanceTest {
    private val team1Id = UUID.randomUUID()
    private val team2Id = UUID.randomUUID()
    private val player1 = UUID.randomUUID()
    private val player2 = UUID.randomUUID()

    private fun matchWithSet(set: MatchSetResult): Match =
        Match(
            id = UUID.randomUUID(),
            publicCode = "TB1084",
            matchFormat = TeamType.SINGLES,
            matchType = MatchType.FULL_MATCH,
            matchDate = LocalDate.of(2026, 3, 1),
            status = MatchStatus.COMPLETED,
            team1 = MatchSide(teamId = team1Id, userIds = listOf(element = player1)),
            team2 = MatchSide(teamId = team2Id, userIds = listOf(element = player2)),
            winnerTeamId = team1Id,
            sets = listOf(element = set),
            eventId = UUID.randomUUID(),
            matchNumber = 1,
        )

    /** Equal ratings, so any movement can only have come from the set's own margin. */
    private fun changesFor(set: MatchSetResult): List<BigDecimal> {
        val request =
            buildRequest(
                match = matchWithSet(set = set),
                ratingsByUser = mapOf(player1 to BigDecimal("3.5"), player2 to BigDecimal("3.5")),
                groupsByUser = mapOf(player1 to null, player2 to null),
            )
        return PerformanceBasedRankingCalculatorImpl()
            .calculate(request = request)
            .response
            .ratingChanges
            .values
            .map { BigDecimal(it.change) }
    }

    private fun tiebreakSet(
        team1Games: Int,
        team2Games: Int,
    ) = MatchSetResult(
        setNumber = 1,
        team1Games = team1Games,
        team2Games = team2Games,
        winnerTeamId = team1Id,
        tiebreakTeam1Points = 7,
        tiebreakTeam2Points = 5,
    )

    @Test
    fun `a set won on a tiebreak moves both ratings`() {
        val changes = changesFor(set = tiebreakSet(team1Games = 7, team2Games = 6))

        changes.shouldHaveSize(size = 2)
        changes.forEach { change -> change.compareTo(other = BigDecimal.ZERO) shouldNotBe 0 }
    }

    @Test
    fun `the winner gains and the loser loses, by the same amount`() {
        val changes = changesFor(set = tiebreakSet(team1Games = 7, team2Games = 6))

        // Zero-sum: nobody is calibrating here, so the pool is conserved (#881).
        changes.fold(initial = BigDecimal.ZERO) { acc, v -> acc + v }.compareTo(other = BigDecimal.ZERO) shouldBe 0
        changes.count { it > BigDecimal.ZERO } shouldBe 1
        changes.count { it < BigDecimal.ZERO } shouldBe 1
    }

    @Test
    fun `the legacy six-six shape is what moved nobody, which is why it was a bug`() {
        // Not an endorsement of the old behaviour -- a record of it. Any row banked before #1084 looks
        // like this, and a re-rate is the only thing that would change its rating.
        val changes = changesFor(set = tiebreakSet(team1Games = 6, team2Games = 6))

        changes.forEach { change -> change.compareTo(other = BigDecimal.ZERO) shouldBe 0 }
    }
}
