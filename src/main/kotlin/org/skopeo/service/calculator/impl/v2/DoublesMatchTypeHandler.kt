// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.calculator.impl.v2

import org.skopeo.dto.RankingCalculationRequest
import org.skopeo.dto.RankingCalculationResponse
import org.skopeo.dto.RatingChange
import org.skopeo.model.Rating
import org.skopeo.model.asRating
import org.skopeo.model.bd
import org.skopeo.model.divideBy
import org.skopeo.model.toStringPrecise
import org.skopeo.service.calculator.AuditTrail
import org.skopeo.service.calculator.impl.v2.PerformanceBasedRankingCalculatorImpl.SetStep
import java.math.BigDecimal

/**
 * Doubles specifics of the v2 calculation (#256, "Scheme 2 — team mean"). A team's rating is the **mean**
 * of its two partners, so the shared per-set/per-team engine in [PerformanceBasedRankingCalculatorImpl]
 * runs team-vs-team on those means; this handler then **splits** each team's change back to its partners
 * by their rating share of the mean (`δ_i = Δ_team · rᵢ / mean_team`). The split uses the fixed
 * within-match starting ratio, so per-set audit deltas sum to the net change. Per the design study,
 * this conserves total rating and reuses the singles calibration cleanly. MIXED_DOUBLES is treated
 * identically to DOUBLES. The shared audit flow lives in [AbstractMatchTypeHandler].
 */
class DoublesMatchTypeHandler(
    request: RankingCalculationRequest,
    auditTrail: AuditTrail,
) : AbstractMatchTypeHandler(request = request, auditTrail = auditTrail) {
    private companion object {
        private val TWO = "2".bd
        private val HUNDRED = "100.0".bd
    }

    /** Per-player running rating during the match, for a faithful per-set audit `ratingAfter`. */
    private val running = mutableMapOf<String, BigDecimal>()

    override val auditLabel: String get() = " (doubles)"

    /** The team's pre-calculation rating: the mean of its two partners (kept fixed for the split ratio). */
    private fun teamMean(teamId: String): BigDecimal =
        playersOf(teamId = teamId).map { it.rating.value.bd }.let { (it[0] + it[1]).divideBy(divisor = TWO) }

    override fun getTeamPreCalculationRating(teamId: String): Rating = teamMean(teamId = teamId).asRating

    // Split the team step by each partner's share of the team mean, carrying a per-partner running rating
    // forward so the audit's `ratingAfter` tracks the individual (not the team mean). teamRatingAfter is unused.
    override fun resolvePlayerSteps(
        teamId: String,
        step: SetStep,
        teamRatingAfter: BigDecimal,
    ): List<PlayerSetResult> {
        val mean = teamMean(teamId = teamId)
        return playersOf(teamId = teamId).map { player ->
            val delta = step.delta * player.rating.value.bd.divideBy(divisor = mean)
            val ratingAfter = running.getOrPut(key = player.playerId) { player.rating.value.bd } + delta
            running[player.playerId] = ratingAfter
            PlayerSetResult(player = player, delta = delta, ratingAfter = ratingAfter)
        }
    }

    override fun generateResponse(newTeamRatingChanges: Map<String, RatingChange>): RankingCalculationResponse {
        val updatedTeams =
            newTeamRatingChanges.map { (teamId, teamChange) ->
                val team = request.teams.getValue(key = teamId)
                val mean = teamChange.previousRating.value.bd
                val netTeamDelta = teamChange.newRating.value.bd - mean
                val perPlayer =
                    team.players.map { player ->
                        val playerChange =
                            playerRatingChange(
                                oldRating = player.rating,
                                delta = netTeamDelta * player.rating.value.bd.divideBy(divisor = mean),
                            )
                        player.copy(rating = playerChange.newRating) to playerChange
                    }
                team.copy(players = perPlayer.map { it.first }) to perPlayer
            }
        return RankingCalculationResponse(
            ratingChanges = updatedTeams.flatMap { it.second }.associate { (player, change) -> player.playerId to change },
            players = updatedTeams.flatMap { it.second }.associate { (player, _) -> player.playerId to player },
            teams = updatedTeams.associate { (team, _) -> team.teamId to team },
        )
    }

    private fun playerRatingChange(
        oldRating: Rating,
        delta: BigDecimal,
    ): RatingChange {
        val previous = oldRating.value.bd
        val newRating = (previous + delta).asRating
        return RatingChange(
            change = delta.toStringPrecise(),
            previousRating = oldRating,
            newRating = newRating,
            percentChange = (delta.divideBy(divisor = previous) * HUNDRED).toStringPrecise(),
            levelChanged = oldRating.publishedLevel.value != newRating.publishedLevel.value,
        )
    }
}
