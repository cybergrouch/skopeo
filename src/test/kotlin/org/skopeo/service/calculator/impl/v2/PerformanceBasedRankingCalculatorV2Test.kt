// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.calculator.impl.v2

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skopeo.dto.RankingCalculationRequest
import org.skopeo.model.MatchScore
import org.skopeo.model.PlayerProfile
import org.skopeo.model.Rating
import org.skopeo.model.RatingCalculationOptions
import org.skopeo.model.SetScore
import org.skopeo.model.Team
import org.skopeo.model.TeamType

class PerformanceBasedRankingCalculatorV2Test {
    private val v2 = PerformanceBasedRankingCalculatorImpl()

    private fun team(
        id: String,
        playerId: String,
        rating: String,
    ): Team {
        val player = PlayerProfile(playerId = playerId, name = playerId, rating = Rating.fromValue(value = rating))
        return Team(teamId = id, name = id, players = listOf(element = player), teamType = TeamType.SINGLES)
    }

    private fun multiSetRequest(
        p1Rating: String,
        p2Rating: String,
        sets: List<Pair<Int, Int>>,
        smoothingEnabled: Boolean = false,
        smoothingFactor: Double = 0.5,
    ): RankingCalculationRequest {
        val options =
            if (smoothingEnabled) {
                RatingCalculationOptions(smoothingEnabled = true, smoothingFactor = smoothingFactor)
            } else {
                null
            }
        return RankingCalculationRequest(
            teams =
                mapOf(
                    "T1" to team(id = "T1", playerId = "P1", rating = p1Rating),
                    "T2" to team(id = "T2", playerId = "P2", rating = p2Rating),
                ),
            matchScore = MatchScore(sets = sets.map { (a, b) -> SetScore(games = mapOf("T1" to a, "T2" to b)) }),
            options = options,
        )
    }

    private fun changeFor(
        result: org.skopeo.service.calculator.RankingCalculationResult,
        playerId: String,
    ): String = result.response.ratingChanges.getValue(key = playerId).change

    @Test
    fun `a single set applies one dominance-scaled step`() {
        // Equal players, a 6-0 set: dominance 1.0, competitive scale 1.0, K 0.16 → ±0.16.
        val request = multiSetRequest(p1Rating = "4.0", p2Rating = "4.0", sets = listOf(element = 6 to 0))

        changeFor(result = v2.calculate(request = request), playerId = "P1").toDouble() shouldBe (0.16 plusOrMinus 0.001)
        changeFor(result = v2.calculate(request = request), playerId = "P2").toDouble() shouldBe (-0.16 plusOrMinus 0.001)
    }

    @Test
    fun `a multi-set match chains per-set (carry-forward) instead of averaging dominance`() {
        // Equal players, a 6-0,6-0,6-0 sweep. Averaging dominance (1.0) would give a single 0.16 step;
        // v2 chains 0.16 + 0.0572 + 0.0204 = 0.2376 (each set evaluated against the widening gap).
        val request = multiSetRequest(p1Rating = "4.0", p2Rating = "4.0", sets = listOf(6 to 0, 6 to 0, 6 to 0))

        val v2Result = v2.calculate(request = request)

        changeFor(result = v2Result, playerId = "P1").toDouble() shouldBe (0.2376 plusOrMinus 0.001)
        changeFor(result = v2Result, playerId = "P2").toDouble() shouldBe (-0.2376 plusOrMinus 0.001)
    }

    @Test
    fun `smoothing is applied once to the net match change, not per set`() {
        // The same sweep: net raw +0.2376; with a 0.5 factor applied once at the end → +0.1188.
        val request =
            multiSetRequest(
                p1Rating = "4.0",
                p2Rating = "4.0",
                sets = listOf(6 to 0, 6 to 0, 6 to 0),
                smoothingEnabled = true,
                smoothingFactor = 0.5,
            )

        changeFor(result = v2.calculate(request = request), playerId = "P1").toDouble() shouldBe (0.1188 plusOrMinus 0.001)
    }

    @Test
    fun `set order changes the v2 result (momentum)`() {
        // Same three sets, different order. Carry-forward makes each set's expectation depend on the
        // running rating, so the order matters (an order-independent average would not change).
        val requestA = multiSetRequest(p1Rating = "4.0", p2Rating = "4.0", sets = listOf(2 to 6, 6 to 3, 6 to 4))
        val requestB = multiSetRequest(p1Rating = "4.0", p2Rating = "4.0", sets = listOf(6 to 4, 6 to 3, 2 to 6))

        val v2A = changeFor(result = v2.calculate(request = requestA), playerId = "P1")
        val v2B = changeFor(result = v2.calculate(request = requestB), playerId = "P1")

        (v2A != v2B) shouldBe true
    }
}
