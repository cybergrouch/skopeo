// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.calculator.impl.v2

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skopeo.common.dto.RankingCalculationRequest
import org.skopeo.domain.model.MatchScore
import org.skopeo.domain.model.PlayerProfile
import org.skopeo.domain.model.Rating
import org.skopeo.domain.model.SetScore
import org.skopeo.domain.model.Team
import org.skopeo.domain.model.TeamType
import java.math.BigDecimal

/**
 * A **level set** has no dominance, and therefore no direction either.
 *
 * Dominance is per set — `stepFor` is called once per set with a single-set `MatchScore` — so this
 * arises only when one set is level (5-5, 6-6), not when sets cancel across a match. That makes it
 * exactly the drawn-set case: a conceded draw, or a retirement while the set is level.
 *
 * The algorithm is dominance-driven: the rating gap only ever enters through `scale`, which
 * **multiplies** dominance, so a level set moves nobody. The delta was therefore always right. What was
 * wrong was the *explanation*: a `SetScore` must name a winner, so a level set carries a designation,
 * and if that designation fell on the lower-rated side the step scored as an upset — a headline
 * attached to a set that moved nothing. The audit is user-facing through the derivation view (#862).
 *
 * The `sign` guard alongside it changes no arithmetic today (`dominance.abs()` is already zero). It is
 * there so the sign stays honest if the formula ever stops multiplying by dominance, and so the
 * "no dominance, no direction" rule is stated once, where a reader looks for it.
 *
 * A level set is not recordable today (`MatchService.setWinner` refuses it), so these exercise the
 * calculator directly. It becomes reachable when drawn matches do.
 */
class ZeroDominanceTest {
    private fun team(
        id: String,
        rating: String,
    ) = Team(
        teamId = id,
        name = id,
        players = listOf(element = PlayerProfile(playerId = "P-$id", name = id, rating = Rating.fromValue(value = rating))),
        teamType = TeamType.SINGLES,
    )

    /** A single level set at 5-5: dominance is (5-5)/10 = 0, whoever the model nominally labels winner. */
    private fun levelSet(
        t1Rating: String,
        t2Rating: String,
    ) = RankingCalculationRequest(
        teams = mapOf("T1" to team(id = "T1", rating = t1Rating), "T2" to team(id = "T2", rating = t2Rating)),
        matchScore =
            MatchScore(
                sets = listOf(element = SetScore(games = mapOf("T1" to 5, "T2" to 5), winnerTeamId = "T1", loserTeamId = "T2")),
                winnerTeamId = "T1",
                loserTeamId = "T2",
            ),
    )

    @Test
    fun `a level set moves neither player, whichever is favoured`() {
        val calculator = PerformanceBasedRankingCalculatorImpl()

        // A large gap in either direction: the gap can only act through scale, which multiplies zero.
        // This held before the fix too — it is the direction, not the magnitude, that was wrong.
        listOf("3.0" to "5.0", "5.0" to "3.0", "4.0" to "4.0").forEach { (t1, t2) ->
            val result = calculator.calculate(request = levelSet(t1Rating = t1, t2Rating = t2))
            result.response.ratingChanges.values.forEach { change ->
                BigDecimal(change.change).compareTo(other = BigDecimal.ZERO) shouldBe 0
            }
        }
    }

    @Test
    fun `a level set is not reported as an upset`() {
        val calculator = PerformanceBasedRankingCalculatorImpl()

        // T1 is the LOWER-rated side and is the set's nominal winner, so without the guard this scores
        // as an upset — a headline explanation attached to a set that moved nobody. Picking the other
        // orientation hides the bug: both sides come out false there anyway.
        val audit = calculator.calculate(request = levelSet(t1Rating = "3.0", t2Rating = "5.0")).audit

        val upsetFlags = audit.mapNotNull { it.context["isUpset"] }
        upsetFlags.isNotEmpty() shouldBe true
        upsetFlags.forEach { it shouldBe "false" }
    }
}
