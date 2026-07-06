// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.calculator.impl.v2

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
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

    private fun doublesTeam(
        id: String,
        ratings: Pair<String, String>,
        format: TeamType = TeamType.DOUBLES,
    ): Team {
        val (rA, rB) = ratings
        val a = PlayerProfile(playerId = "${id}a", name = "${id}a", rating = Rating.fromValue(value = rA))
        val b = PlayerProfile(playerId = "${id}b", name = "${id}b", rating = Rating.fromValue(value = rB))
        return Team(teamId = id, name = id, players = listOf(a, b), teamType = format)
    }

    private fun doublesRequest(
        t1: Pair<String, String>,
        t2: Pair<String, String>,
        sets: List<Pair<Int, Int>>,
        format: TeamType = TeamType.DOUBLES,
    ): RankingCalculationRequest =
        RankingCalculationRequest(
            teams =
                mapOf(
                    "T1" to doublesTeam(id = "T1", ratings = t1, format = format),
                    "T2" to doublesTeam(id = "T2", ratings = t2, format = format),
                ),
            matchScore = MatchScore(sets = sets.map { (a, b) -> SetScore(games = mapOf("T1" to a, "T2" to b)) }),
        )

    @Test
    fun `doubles with equal partners moves each partner like the singles result`() {
        // Both teams average 4.0, a 6-0 set → team Δ ±0.16. Equal partners (rᵢ = mean) each take the full
        // team Δ, so the team mean moves by ±0.16 exactly as the equivalent singles player would.
        val result = v2.calculate(request = doublesRequest(t1 = "4.0" to "4.0", t2 = "4.0" to "4.0", sets = listOf(element = 6 to 0)))

        changeFor(result = result, playerId = "T1a").toDouble() shouldBe (0.16 plusOrMinus 0.001)
        changeFor(result = result, playerId = "T1b").toDouble() shouldBe (0.16 plusOrMinus 0.001)
        changeFor(result = result, playerId = "T2a").toDouble() shouldBe (-0.16 plusOrMinus 0.001)
        changeFor(result = result, playerId = "T2b").toDouble() shouldBe (-0.16 plusOrMinus 0.001)
    }

    @Test
    fun `doubles splits the team change by each partner's share of the team mean`() {
        // T1 = 5.0 & 3.0 (mean 4.0) vs T2 = 4.0 & 4.0 (mean 4.0): equal means → team Δ ±0.16, but T1's
        // change splits proportionally: 0.16·5/4 = 0.20 to the stronger partner, 0.16·3/4 = 0.12 to the weaker.
        val result = v2.calculate(request = doublesRequest(t1 = "5.0" to "3.0", t2 = "4.0" to "4.0", sets = listOf(element = 6 to 0)))

        changeFor(result = result, playerId = "T1a").toDouble() shouldBe (0.20 plusOrMinus 0.001)
        changeFor(result = result, playerId = "T1b").toDouble() shouldBe (0.12 plusOrMinus 0.001)
    }

    @Test
    fun `doubles conserves total rating across the four players`() {
        val result = v2.calculate(request = doublesRequest(t1 = "5.0" to "3.0", t2 = "4.5" to "3.5", sets = listOf(6 to 2, 4 to 6, 6 to 3)))

        val net = result.response.ratingChanges.values.sumOf { it.change.toDouble() }
        net shouldBe (0.0 plusOrMinus 0.001)
    }

    @Test
    fun `mixed doubles is rated identically to doubles`() {
        val doubles = v2.calculate(request = doublesRequest(t1 = "5.0" to "3.0", t2 = "4.0" to "4.0", sets = listOf(element = 6 to 1)))
        val mixed =
            v2.calculate(
                request =
                    doublesRequest(
                        t1 = "5.0" to "3.0",
                        t2 = "4.0" to "4.0",
                        sets = listOf(element = 6 to 1),
                        format = TeamType.MIXED_DOUBLES,
                    ),
            )

        changeFor(result = mixed, playerId = "T1a") shouldBe changeFor(result = doubles, playerId = "T1a")
        changeFor(result = mixed, playerId = "T1b") shouldBe changeFor(result = doubles, playerId = "T1b")
    }

    @Test
    fun `team-level change depends only on the team mean, not the within-team spread (#265)`() {
        // #265 decision: upset/gap is decided by the team MEAN, so two 7.0-combined pairs — {3.0,4.0} and
        // {3.5,3.5}, both mean 3.5 — get the identical team-level change against the same opponents and score.
        // Only the within-team split differs (proportional to each partner's share of the mean); the team mean
        // itself moves by the same Δ_team. This locks in that within-team spread must NOT affect the team-level
        // upset/gap treatment (a spread-aware alternative was considered and rejected — see the algorithm doc §7.4).
        val opponents = "4.0" to "4.0"
        val score = listOf(6 to 4, 6 to 4)
        val lopsided = v2.calculate(request = doublesRequest(t1 = "3.0" to "4.0", t2 = opponents, sets = score))
        val even = v2.calculate(request = doublesRequest(t1 = "3.5" to "3.5", t2 = opponents, sets = score))

        // Sum of the two partners' deltas = 2·Δ_team, i.e. the team-level change; it must match across both splits.
        val lopsidedTeamDelta =
            changeFor(result = lopsided, playerId = "T1a").toDouble() + changeFor(result = lopsided, playerId = "T1b").toDouble()
        val evenTeamDelta =
            changeFor(result = even, playerId = "T1a").toDouble() + changeFor(result = even, playerId = "T1b").toDouble()

        lopsidedTeamDelta shouldBe (evenTeamDelta plusOrMinus 1e-9)
        // Sanity: it is a positive (upset) move — the 3.5-mean pair beat the higher 4.0-mean pair.
        evenTeamDelta shouldBeGreaterThan 0.0
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
