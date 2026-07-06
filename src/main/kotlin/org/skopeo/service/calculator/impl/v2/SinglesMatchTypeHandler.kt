// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.calculator.impl.v2

import org.skopeo.dto.RankingCalculationRequest
import org.skopeo.dto.RankingCalculationResponse
import org.skopeo.dto.RatingChange
import org.skopeo.model.PlayerProfile
import org.skopeo.model.Rating
import org.skopeo.model.SetScore
import org.skopeo.model.toStringPrecise
import org.skopeo.service.calculator.AuditEntry
import org.skopeo.service.calculator.AuditTrail
import org.skopeo.service.calculator.impl.v2.PerformanceBasedRankingCalculatorImpl.Companion.COMPETITIVE_THRESHOLD_PCT
import org.skopeo.service.calculator.impl.v2.PerformanceBasedRankingCalculatorImpl.Companion.K_FACTOR_NTRP
import org.skopeo.service.calculator.impl.v2.PerformanceBasedRankingCalculatorImpl.Companion.UPSET_MULTIPLIER
import org.skopeo.service.calculator.impl.v2.PerformanceBasedRankingCalculatorImpl.SetStep
import java.math.BigDecimal

/**
 * Singles specifics of the v2 calculation (#110): a "team" is one player, so the team rating is that
 * player's rating and the response is keyed by the single player. The per-set/per-team math common to
 * singles and doubles lives in [PerformanceBasedRankingCalculatorImpl]; this handler supplies only the
 * singles-shaped inputs and output (paving the way for a doubles handler).
 */
class SinglesMatchTypeHandler(
    private val request: RankingCalculationRequest,
    private val auditTrail: AuditTrail,
) : MatchTypeHandler {
    private fun getPlayer(teamId: String): PlayerProfile = request.teams.getValue(key = teamId).getSinglePlayer()

    override fun getTeamPreCalculationRating(teamId: String): Rating = getPlayer(teamId = teamId).rating

    override fun createSetCalculationAudit(
        teamId: String,
        setScore: SetScore,
        index: Int,
        step: SetStep,
        ratingAfter: BigDecimal,
    ) {
        auditTrail.add(
            entry =
                setAudit(
                    teamId = teamId,
                    setScore = setScore,
                    index = index,
                    step = step,
                    ratingAfter = ratingAfter,
                ),
        )
    }

    override fun generateResponse(newTeamRatingChanges: Map<String, RatingChange>): RankingCalculationResponse =
        newTeamRatingChanges
            .map { (teamId, ratingChange) ->
                request.teams.getValue(key = teamId).copy(
                    players = listOf(element = getPlayer(teamId = teamId).copy(rating = ratingChange.newRating)),
                ) to ratingChange
            }.let { teamsWithChange ->
                RankingCalculationResponse(
                    ratingChanges = teamsWithChange.associate { (team, ratingChange) -> team.getSinglePlayer().playerId to ratingChange },
                    players = teamsWithChange.associate { (team, _) -> team.getSinglePlayer().let { it.playerId to it } },
                    teams = teamsWithChange.associate { (team, _) -> team.teamId to team },
                )
            }

    private fun setAudit(
        teamId: String,
        setScore: SetScore,
        index: Int,
        step: SetStep,
        ratingAfter: BigDecimal,
    ): AuditEntry {
        val player = getPlayer(teamId = teamId)
        val self = setScore.games.getValue(key = teamId)
        val opponent = setScore.games.filterKeys { it != teamId }.values.sum()
        return AuditEntry(
            message =
                "Set ${index + 1} - ${player.name}: $self-$opponent, dominance ${step.dominance.toStringPrecise()}, " +
                    "scale ${step.scale.toStringPrecise()} -> Δ ${step.delta.toStringPrecise()}, rating ${ratingAfter.toStringPrecise()}",
            context =
                mapOf(
                    "playerId" to player.playerId,
                    "setIndex" to index.toString(),
                    "setScore" to "$self-$opponent",
                    "dominance" to step.dominance.toStringPrecise(),
                    "scale" to step.scale.toStringPrecise(),
                    "ratingGap" to step.ratingGap.toStringPrecise(),
                    "normalizedGap" to step.normalizedGap.toStringPrecise(),
                    "competitiveThresholdPct" to COMPETITIVE_THRESHOLD_PCT.toStringPrecise(),
                    "isUpset" to step.isUpset.toString(),
                    "upsetMultiplier" to UPSET_MULTIPLIER.toStringPrecise(),
                    "kFactor" to K_FACTOR_NTRP.toStringPrecise(),
                    "delta" to step.delta.toStringPrecise(),
                    "ratingAfter" to ratingAfter.toStringPrecise(),
                ),
        )
    }
}
