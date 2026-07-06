// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.calculator.impl.v2

import org.skopeo.dto.RankingCalculationRequest
import org.skopeo.dto.RankingCalculationResponse
import org.skopeo.dto.RatingChange
import org.skopeo.model.PlayerProfile
import org.skopeo.model.Rating
import org.skopeo.model.SetScore
import org.skopeo.model.asRating
import org.skopeo.model.bd
import org.skopeo.model.divideBy
import org.skopeo.model.toStringPrecise
import org.skopeo.service.calculator.AuditEntry
import org.skopeo.service.calculator.AuditTrail
import org.skopeo.service.calculator.impl.v2.PerformanceBasedRankingCalculatorImpl.Companion.COMPETITIVE_THRESHOLD_PCT
import org.skopeo.service.calculator.impl.v2.PerformanceBasedRankingCalculatorImpl.Companion.K_FACTOR_NTRP
import org.skopeo.service.calculator.impl.v2.PerformanceBasedRankingCalculatorImpl.Companion.UPSET_MULTIPLIER
import org.skopeo.service.calculator.impl.v2.PerformanceBasedRankingCalculatorImpl.SetStep
import java.math.BigDecimal

/**
 * Doubles specifics of the v2 calculation (#256, "Scheme 2 — team mean"). A team's rating is the **mean**
 * of its two partners, so the shared per-set/per-team engine in [PerformanceBasedRankingCalculatorImpl]
 * runs team-vs-team on those means; this handler then **splits** each team's change back to its partners
 * by their rating share of the mean (`δ_i = Δ_team · rᵢ / mean_team`). The split uses the fixed
 * within-match starting ratio, so per-set audit deltas sum to the net change. Per the design study,
 * this conserves total rating and reuses the singles calibration cleanly. MIXED_DOUBLES is treated
 * identically to DOUBLES.
 */
class DoublesMatchTypeHandler(
    private val request: RankingCalculationRequest,
    private val auditTrail: AuditTrail,
) : MatchTypeHandler {
    private companion object {
        private val TWO = "2".bd
        private val HUNDRED = "100.0".bd
    }

    /** Per-player running rating during the match, for a faithful per-set audit `ratingAfter`. */
    private val running = mutableMapOf<String, BigDecimal>()

    private fun playersOf(teamId: String): List<PlayerProfile> = request.teams.getValue(key = teamId).players

    /** The team's pre-calculation rating: the mean of its two partners (kept fixed for the split ratio). */
    private fun teamMean(teamId: String): BigDecimal =
        playersOf(teamId = teamId).map { it.rating.value.bd }.let { (it[0] + it[1]).divideBy(divisor = TWO) }

    override fun getTeamPreCalculationRating(teamId: String): Rating = teamMean(teamId = teamId).asRating

    override fun createSetCalculationAudit(
        teamId: String,
        setScore: SetScore,
        index: Int,
        step: SetStep,
        ratingAfter: BigDecimal,
    ) {
        playersOf(teamId = teamId).forEach { player ->
            auditTrail.add(entry = setAudit(player = player, teamId = teamId, setScore = setScore, index = index, step = step))
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

    private fun setAudit(
        player: PlayerProfile,
        teamId: String,
        setScore: SetScore,
        index: Int,
        step: SetStep,
    ): AuditEntry {
        val self = setScore.games.getValue(key = teamId)
        val opponent = setScore.games.filterKeys { it != teamId }.values.sum()
        val scoreLabel = "$self-$opponent"
        // Split the team's per-set delta by this partner's share of the team mean, and carry each partner's
        // running rating forward so the audit's `ratingAfter` tracks the individual (not the team mean).
        val playerDelta = step.delta * player.rating.value.bd.divideBy(divisor = teamMean(teamId = teamId))
        val ratingAfter = running.getOrPut(key = player.playerId) { player.rating.value.bd } + playerDelta
        running[player.playerId] = ratingAfter
        return AuditEntry(
            message =
                "Set ${index + 1} - ${player.name} (doubles): $scoreLabel, dominance ${step.dominance.toStringPrecise()}, " +
                    "scale ${step.scale.toStringPrecise()} -> Δ ${playerDelta.toStringPrecise()}, rating ${ratingAfter.toStringPrecise()}",
            // The dominance/scale/gap/upset factors are the team's (shared by partners); delta is this
            // player's split share, so RatingCalculationService builds correct per-player history.
            context =
                mapOf(
                    "playerId" to player.playerId,
                    "setIndex" to index.toString(),
                    "setScore" to scoreLabel,
                    "dominance" to step.dominance.toStringPrecise(),
                    "scale" to step.scale.toStringPrecise(),
                    "ratingGap" to step.ratingGap.toStringPrecise(),
                    "normalizedGap" to step.normalizedGap.toStringPrecise(),
                    "competitiveThresholdPct" to COMPETITIVE_THRESHOLD_PCT.toStringPrecise(),
                    "isUpset" to step.isUpset.toString(),
                    "upsetMultiplier" to UPSET_MULTIPLIER.toStringPrecise(),
                    "kFactor" to K_FACTOR_NTRP.toStringPrecise(),
                    "delta" to playerDelta.toStringPrecise(),
                    "ratingAfter" to ratingAfter.toStringPrecise(),
                ),
        )
    }
}
