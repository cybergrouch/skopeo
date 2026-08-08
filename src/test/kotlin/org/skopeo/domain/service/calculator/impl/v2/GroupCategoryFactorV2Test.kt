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

/**
 * The binary group-category factor (#719): the calculator only compares the two sides' opaque `group`
 * labels for equality. Same group (or an absent group on either side) leaves the delta unchanged;
 * different groups zero it, and the decision is surfaced to the audit trail.
 */
class GroupCategoryFactorV2Test {
    private val v2 = PerformanceBasedRankingCalculatorImpl()

    private fun request(
        p1Group: String?,
        p2Group: String?,
    ): RankingCalculationRequest {
        val player1 = PlayerProfile(playerId = "P1", name = "P1", rating = Rating.fromValue(value = "4.0"), group = p1Group)
        val player2 = PlayerProfile(playerId = "P2", name = "P2", rating = Rating.fromValue(value = "4.0"), group = p2Group)
        return RankingCalculationRequest(
            teams =
                mapOf(
                    "T1" to Team(teamId = "T1", name = "T1", players = listOf(element = player1), teamType = TeamType.SINGLES),
                    "T2" to Team(teamId = "T2", name = "T2", players = listOf(element = player2), teamType = TeamType.SINGLES),
                ),
            matchScore = MatchScore(sets = listOf(element = SetScore(games = mapOf("T1" to 6, "T2" to 0)))),
        )
    }

    private fun changeFor(
        request: RankingCalculationRequest,
        playerId: String,
    ): String = v2.calculate(request = request).response.ratingChanges.getValue(key = playerId).change

    @Test
    fun `matching groups leave the delta identical to the ungrouped result`() {
        // Equal players, 6-0: baseline ±0.16. A shared group must not perturb that.
        val baseline = changeFor(request = request(p1Group = null, p2Group = null), playerId = "P1")
        val matched = changeFor(request = request(p1Group = "Male", p2Group = "Male"), playerId = "P1")

        matched shouldBe baseline
    }

    @Test
    fun `mismatched groups zero both players' deltas`() {
        val req = request(p1Group = "Male", p2Group = "Female")

        changeFor(request = req, playerId = "P1").toDouble() shouldBe 0.0
        changeFor(request = req, playerId = "P2").toDouble() shouldBe 0.0
    }

    @Test
    fun `an absent group on either side is treated as the same group`() {
        val baseline = changeFor(request = request(p1Group = null, p2Group = null), playerId = "P1")

        changeFor(request = request(p1Group = "Male", p2Group = null), playerId = "P1") shouldBe baseline
        changeFor(request = request(p1Group = null, p2Group = "Female"), playerId = "P1") shouldBe baseline
    }

    @Test
    fun `a zeroed cross-group delta is explained in the audit trail`() {
        val audit = v2.calculate(request = request(p1Group = "Male", p2Group = "Female")).audit
        val groupEntry = audit.single { it.context.containsKey(key = "groupCategoryFactor") }

        groupEntry.context.getValue(key = "groupCategoryFactor") shouldBe "0.000000"
        groupEntry.context.getValue(key = "team1Group") shouldBe "Male"
        groupEntry.context.getValue(key = "team2Group") shouldBe "Female"
    }

    @Test
    fun `a matching-group factor is audited as one`() {
        val audit = v2.calculate(request = request(p1Group = "Male", p2Group = "Male")).audit
        val groupEntry = audit.single { it.context.containsKey(key = "groupCategoryFactor") }

        groupEntry.context.getValue(key = "groupCategoryFactor") shouldBe "1.000000"
    }
}
