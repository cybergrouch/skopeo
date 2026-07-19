// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.calculator.impl.v2

import org.skopeo.dto.RankingCalculationResponse
import org.skopeo.dto.RatingChange
import org.skopeo.model.Rating
import org.skopeo.model.SetScore
import org.skopeo.service.calculator.impl.v2.PerformanceBasedRankingCalculatorImpl.SetStep
import java.math.BigDecimal

/**
 * The match-format-specific parts of the v2 calculation (#110), so [PerformanceBasedRankingCalculatorImpl]
 * can run the per-set/per-team math common to singles and doubles while a handler supplies what differs:
 * the team's pre-calculation rating, how a set audit reads for that format, and how team-level changes map
 * back onto the players/teams in the response. Singles is implemented in [SinglesMatchTypeHandler];
 * doubles will add its own handler (balancing the change across the two partners).
 */
interface MatchTypeHandler {
    /** The rating a team enters the calculation with (for singles, the single player's rating). */
    fun getTeamPreCalculationRating(teamId: String): Rating

    /**
     * The team's **effective** rating for the per-set gap/expectation math (issue #486): the true
     * [getTeamPreCalculationRating] with this side's handicap (if any) deducted, floored at the NTRP
     * minimum. Only this feeds the delta computation; the true rating still drives previous/new and the
     * doubles split. With no handicap this equals the true pre-calculation rating.
     */
    fun getTeamEffectiveRating(teamId: String): BigDecimal

    /** Record the audit entry for one team's computed step in one set. */
    fun createSetCalculationAudit(
        teamId: String,
        setScore: SetScore,
        index: Int,
        step: SetStep,
        ratingAfter: BigDecimal,
    )

    /** Map the per-team rating changes onto the response's players/teams for this format. */
    fun generateResponse(newTeamRatingChanges: Map<String, RatingChange>): RankingCalculationResponse
}
