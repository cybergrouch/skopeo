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
import org.skopeo.service.calculator.impl.v1.createSinglesRequest
import org.skopeo.service.calculator.impl.v1.PerformanceBasedRankingCalculatorImpl as V1Calculator

class PerformanceBasedRankingCalculatorV2Test {
    private val v2 = PerformanceBasedRankingCalculatorImpl()
    private val v1 = V1Calculator()

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
    fun `a single-set match yields exactly the v1 result (parity), with and without smoothing`() {
        // Expected win, upset, and shutout — with smoothing on and off — must match v1 set-for-set.
        listOf(
            createSinglesRequest(p1Rating = "4.5", p2Rating = "4.0", p1Games = 6, p2Games = 2, winner = "T1"),
            // An upset: the lower-rated player wins.
            createSinglesRequest(p1Rating = "4.0", p2Rating = "4.5", p1Games = 6, p2Games = 4, winner = "T1"),
            createSinglesRequest(p1Rating = "4.5", p2Rating = "4.0", p1Games = 6, p2Games = 0, winner = "T1"),
            createSinglesRequest(p1Rating = "4.5", p2Rating = "4.0", p1Games = 6, p2Games = 2, winner = "T1", smoothingEnabled = true),
        ).forEach { request ->
            val a = v2.calculate(request = request)
            val b = v1.calculate(request = request)
            changeFor(result = a, playerId = "P1") shouldBe changeFor(result = b, playerId = "P1")
            changeFor(result = a, playerId = "P2") shouldBe changeFor(result = b, playerId = "P2")
        }
    }

    @Test
    fun `a multi-set match chains per-set (carry-forward) instead of averaging dominance`() {
        // Equal players, a 6-0,6-0,6-0 sweep. v1 averages dominance (1.0) into one 0.16 step.
        // v2 chains: 0.16 + 0.0572 + 0.0204 = 0.2376 (each set evaluated against the widening gap).
        val request = multiSetRequest(p1Rating = "4.0", p2Rating = "4.0", sets = listOf(6 to 0, 6 to 0, 6 to 0))

        val v2Result = v2.calculate(request = request)
        val v1Result = v1.calculate(request = request)

        changeFor(result = v1Result, playerId = "P1").toDouble() shouldBe (0.16 plusOrMinus 0.001)
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
    fun `set order changes the v2 result (momentum) but not the order-independent v1 average`() {
        // Same three sets, different order. v1 averages dominance → order-independent. v2 carries the
        // rating forward set-by-set, so the order (and thus each set's expectation) matters.
        val requestA = multiSetRequest(p1Rating = "4.0", p2Rating = "4.0", sets = listOf(2 to 6, 6 to 3, 6 to 4))
        val requestB = multiSetRequest(p1Rating = "4.0", p2Rating = "4.0", sets = listOf(6 to 4, 6 to 3, 2 to 6))

        // Averaging is order-independent.
        changeFor(result = v1.calculate(request = requestA), playerId = "P1") shouldBe
            changeFor(result = v1.calculate(request = requestB), playerId = "P1")

        // Carry-forward makes the set order matter.
        val v2A = changeFor(result = v2.calculate(request = requestA), playerId = "P1")
        val v2B = changeFor(result = v2.calculate(request = requestB), playerId = "P1")
        (v2A != v2B) shouldBe true
    }
}
