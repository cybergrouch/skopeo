// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.calculator.impl.v2

import org.skopeo.dto.RankingCalculationRequest
import org.skopeo.dto.RankingCalculationResponse
import org.skopeo.dto.RatingChange
import org.skopeo.model.PlayerProfile
import org.skopeo.model.Rating
import org.skopeo.service.calculator.AuditTrail
import org.skopeo.service.calculator.impl.v2.PerformanceBasedRankingCalculatorImpl.SetStep
import java.math.BigDecimal

/**
 * Singles specifics of the v2 calculation (#110): a "team" is one player, so the team rating is that
 * player's rating and the response is keyed by the single player. The per-set/per-team math common to
 * singles and doubles lives in [PerformanceBasedRankingCalculatorImpl]; the shared audit flow lives in
 * [AbstractMatchTypeHandler]. This handler supplies only the singles-shaped inputs and output.
 */
class SinglesMatchTypeHandler(
    request: RankingCalculationRequest,
    auditTrail: AuditTrail,
) : AbstractMatchTypeHandler(request = request, auditTrail = auditTrail) {
    private fun getPlayer(teamId: String): PlayerProfile = request.teams.getValue(key = teamId).getSinglePlayer()

    override fun getTeamPreCalculationRating(teamId: String): Rating = getPlayer(teamId = teamId).rating

    // The single player takes the whole team step, and the team's calculated rating is theirs.
    override fun resolvePlayerSteps(
        teamId: String,
        step: SetStep,
        teamRatingAfter: BigDecimal,
    ): List<PlayerSetResult> =
        listOf(
            element = PlayerSetResult(player = getPlayer(teamId = teamId), delta = step.delta, ratingAfter = teamRatingAfter),
        )

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
}
