// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.rating

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skopeo.domain.model.Match
import org.skopeo.domain.model.MatchCompletionReason
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
 * A retirement **during a tiebreak** must not blow up the rating input (#972).
 *
 * `LiveMatchService` banks the in-progress tiebreak as-is, so 6-6 (4-3) is a legitimate stored row.
 * [org.skopeo.domain.model.TiebreakScore] enforces real tiebreak rules — seven points, a two-point
 * margin — because at the stateless `/api/v1/calculate-ranking` boundary they catch a typo. Handing
 * it a partial tiebreak throws `IllegalArgumentException` from inside a calculation run, failing the
 * whole batch rather than one match.
 *
 * Unit-level rather than through the service on purpose: `buildRequest` is the boundary where stored
 * rows become calculator input, and it takes three arguments, so the case is stated without a
 * database or a rating pipeline in the way.
 */
class AbandonedTiebreakRatingInputTest {
    private val team1Id = UUID.randomUUID()
    private val team2Id = UUID.randomUUID()
    private val player1 = UUID.randomUUID()
    private val player2 = UUID.randomUUID()

    private fun matchWithSet(set: MatchSetResult): Match =
        Match(
            id = UUID.randomUUID(),
            publicCode = "TB0001",
            matchFormat = TeamType.SINGLES,
            matchType = MatchType.FULL_MATCH,
            matchDate = LocalDate.of(2026, 3, 1),
            status = MatchStatus.COMPLETED,
            completionReason = MatchCompletionReason.RETIRED,
            team1 = MatchSide(teamId = team1Id, userIds = listOf(element = player1)),
            team2 = MatchSide(teamId = team2Id, userIds = listOf(element = player2)),
            // The retiring player was ahead in the tiebreak; the match still goes to the opponent.
            winnerTeamId = team2Id,
            sets = listOf(element = set),
            eventId = UUID.randomUUID(),
            matchNumber = 1,
        )

    private fun requestFor(set: MatchSetResult) =
        buildRequest(
            match = matchWithSet(set = set),
            ratingsByUser =
                mapOf(
                    player1 to BigDecimal("3.5"),
                    player2 to BigDecimal("3.5"),
                ),
            groupsByUser = mapOf(player1 to null, player2 to null),
        )

    @Test
    fun `a retirement during a tiebreak builds a rating input instead of throwing`() {
        val request =
            requestFor(
                set =
                    MatchSetResult(
                        setNumber = 1,
                        team1Games = 6,
                        team2Games = 6,
                        winnerTeamId = team1Id,
                        tiebreakTeam1Points = 4,
                        tiebreakTeam2Points = 3,
                        abandoned = true,
                    ),
            )

        // The set survives with its games — it is the TIEBREAK that is dropped, not the set. Games
        // are the only thing dominance counts, so the rating is identical either way.
        request.matchScore.sets.size shouldBe 1
        val set = request.matchScore.sets.single()
        set.games.values.toList() shouldBe listOf(6, 6)
        set.tiebreak shouldBe null
    }

    @Test
    fun `a tiebreak that actually finished is still handed to the calculator`() {
        // The carve-out must not swallow real tiebreaks: 7-5 is a completed tiebreak and belongs in
        // the input, so the fix cannot be "never send a tiebreak".
        val request =
            requestFor(
                set =
                    MatchSetResult(
                        setNumber = 1,
                        team1Games = 7,
                        team2Games = 6,
                        winnerTeamId = team1Id,
                        tiebreakTeam1Points = 7,
                        tiebreakTeam2Points = 5,
                    ),
            )

        val tiebreak = request.matchScore.sets.single().tiebreak
        tiebreak.shouldNotBeNull()
        tiebreak.winnerTeamId shouldBe team1Id.toString()
    }

    @Test
    fun `a tiebreak reaching seven but not by two is still unfinished`() {
        // 7-6 is not a won tiebreak — play continues to 8-6. The margin rule is the second half of
        // the invariant and a check on only the first would let this through and throw.
        val request =
            requestFor(
                set =
                    MatchSetResult(
                        setNumber = 1,
                        team1Games = 6,
                        team2Games = 6,
                        winnerTeamId = team1Id,
                        tiebreakTeam1Points = 7,
                        tiebreakTeam2Points = 6,
                        abandoned = true,
                    ),
            )

        request.matchScore.sets.single().tiebreak shouldBe null
    }

    @Test
    fun `a retirement where the retiree led the only set builds a request rather than throwing`() {
        // The canonical #972 case: 5-1 to the player who then pulled out. The SET is team1's; the
        // MATCH is team2's. MatchScore defaults the loser to whoever lost the most sets, which here
        // names team2 — the designated winner — as the loser too, and `init` throws. Inside a
        // calculation run that fails the whole batch, not just this match.
        val request =
            requestFor(
                set =
                    MatchSetResult(
                        setNumber = 1,
                        team1Games = 5,
                        team2Games = 1,
                        winnerTeamId = team1Id,
                        abandoned = true,
                    ),
            )

        request.matchScore.winnerTeamId shouldBe team2Id.toString()
        request.matchScore.loserTeamId shouldBe team1Id.toString()
    }

    @Test
    fun `a retirement is rated on the tennis played, not on who took the match`() {
        // §10 of the algorithm doc: a retirement rates on the real score. The player who led 5-1 and
        // then pulled out played the better tennis, and the rating follows that rather than the
        // paperwork — which is the entire reason the designated winner is kept separate from the
        // derived set winner.
        val request =
            requestFor(
                set =
                    MatchSetResult(
                        setNumber = 1,
                        team1Games = 5,
                        team2Games = 1,
                        winnerTeamId = team1Id,
                        abandoned = true,
                    ),
            )

        val changes = PerformanceBasedRankingCalculatorImpl().calculate(request = request).response.ratingChanges

        val retireeChange = BigDecimal(changes.getValue(key = player1.toString()).change)
        retireeChange.signum() shouldBe 1
    }

    @Test
    fun `a set with no tiebreak at all is unaffected`() {
        val request =
            requestFor(
                set =
                    MatchSetResult(
                        setNumber = 1,
                        team1Games = 5,
                        team2Games = 1,
                        winnerTeamId = team1Id,
                        abandoned = true,
                    ),
            )

        request.matchScore.sets.single().tiebreak shouldBe null
    }
}
