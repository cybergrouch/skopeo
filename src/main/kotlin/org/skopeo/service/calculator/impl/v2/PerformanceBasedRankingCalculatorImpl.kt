// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.calculator.impl.v2

import org.skopeo.dto.RankingCalculationRequest
import org.skopeo.dto.RatingChange
import org.skopeo.model.MatchScore
import org.skopeo.model.Rating
import org.skopeo.model.RatingCalculationOptions
import org.skopeo.model.TeamType
import org.skopeo.model.asRating
import org.skopeo.model.bd
import org.skopeo.model.calculateDominanceFactor
import org.skopeo.model.divideBy
import org.skopeo.model.toStringPrecise
import org.skopeo.service.calculator.AuditTrail
import org.skopeo.service.calculator.RankingCalculationResult
import org.skopeo.service.calculator.RankingCalculator
import java.math.BigDecimal

/**
 * Per-set sequential rating calculator (issue #110). Unlike v1 — which averages dominance across all
 * sets into one change — v2 treats each set like a one-set match: it runs the same per-set
 * dominance/expectation/scale logic against the players' *current in-match* ratings, carries the raw
 * result forward into the next set, and only at the **end of the match** applies rate smoothing once
 * to the net change. So momentum within a match is reflected, and a single-set match yields exactly the
 * v1 result. The per-set steps are emitted to the audit trail (the service assembles the breakdown #97).
 */
class PerformanceBasedRankingCalculatorImpl : RankingCalculator {
    /** One player's computed step for a single set, against their current in-match rating. */
    data class SetStep(
        val dominance: BigDecimal,
        val scale: BigDecimal,
        val ratingGap: BigDecimal,
        val normalizedGap: BigDecimal,
        val isUpset: Boolean,
        val delta: BigDecimal,
    )

    companion object {
        private val ZERO = "0.0".bd
        private val ONE = "1.0".bd
        internal val NTRP_MIN = "1.0".bd
        private val NTRP_MAX = "7.0".bd
        private val NTRP_RANGE = NTRP_MAX - NTRP_MIN // 6.0
        internal val K_FACTOR_NTRP = "0.16".bd
        internal val COMPETITIVE_THRESHOLD_PCT = "0.083".bd // 0.5 NTRP / 6.0 range
        internal val UPSET_MULTIPLIER = "2.0".bd
    }

    override fun calculate(request: RankingCalculationRequest): RankingCalculationResult {
        val audit = AuditTrail()

        val matchTypeHandler = handlerFor(request = request, audit = audit)

        val teamIds = request.teams.keys.toList()
        val team1Id = teamIds[0]
        val team2Id = teamIds[1]
        val options = request.options ?: RatingCalculationOptions()
        val matchTypeFactor = options.matchTypeFactor.bd

        // TRUE pre-calculation ratings drive previous/new and (for doubles) the split ratio.
        val team1PreCalculationRatingBD =
            matchTypeHandler.getTeamPreCalculationRating(teamId = team1Id).value.bd
        val team2PreCalculationRatingBD =
            matchTypeHandler.getTeamPreCalculationRating(teamId = team2Id).value.bd

        // EFFECTIVE ratings (true − handicap, issue #486) seed the per-set gap/expectation math only. With
        // no handicap these equal the true ratings, so unhandicapped matches are byte-for-byte unchanged.
        var currentTeam1CalculatedRatingBD = matchTypeHandler.getTeamEffectiveRating(teamId = team1Id)
        var currentTeam2CalculatedRatingBD = matchTypeHandler.getTeamEffectiveRating(teamId = team2Id)

        // TRUE running ratings track the delta applied to the true baseline — used for the audit's per-set
        // `ratingAfter` (so history reads against true ratings) and for the net change. Without a handicap
        // these stay lock-step with the effective running ratings; with one they diverge by the handicap.
        var trueTeam1RunningBD = team1PreCalculationRatingBD
        var trueTeam2RunningBD = team2PreCalculationRatingBD

        request.matchScore.sets.forEachIndexed { index, set ->
            val setScore =
                MatchScore(
                    sets = listOf(element = set),
                )
            val step1 =
                stepFor(
                    subjectCurrentCalculatedRating = currentTeam1CalculatedRatingBD,
                    oppositionCurrentCalculatedRating = currentTeam2CalculatedRatingBD,
                    set = setScore,
                    teamId = team1Id,
                    matchTypeFactor = matchTypeFactor,
                )
            val step2 =
                stepFor(
                    subjectCurrentCalculatedRating = currentTeam2CalculatedRatingBD,
                    oppositionCurrentCalculatedRating = currentTeam1CalculatedRatingBD,
                    set = setScore,
                    teamId = team2Id,
                    matchTypeFactor = matchTypeFactor,
                )
            // The effective running rating carries the (handicapped) gap forward set-to-set.
            currentTeam1CalculatedRatingBD = clamp(value = currentTeam1CalculatedRatingBD + step1.delta)
            currentTeam2CalculatedRatingBD = clamp(value = currentTeam2CalculatedRatingBD + step2.delta)
            // The true running rating carries the same deltas onto the true baseline for the audit.
            trueTeam1RunningBD = clamp(value = trueTeam1RunningBD + step1.delta)
            trueTeam2RunningBD = clamp(value = trueTeam2RunningBD + step2.delta)

            matchTypeHandler.createSetCalculationAudit(
                teamId = team1Id,
                setScore = set,
                index = index,
                step = step1,
                ratingAfter = trueTeam1RunningBD,
            )

            matchTypeHandler.createSetCalculationAudit(
                teamId = team2Id,
                setScore = set,
                index = index,
                step = step2,
                ratingAfter = trueTeam2RunningBD,
            )
        }

        // Net change is applied to the TRUE pre-calculation rating (the handicap only shaped the deltas).
        val newTeam1Rating =
            smoothAndClamp(
                previous = team1PreCalculationRatingBD,
                netChange = trueTeam1RunningBD - team1PreCalculationRatingBD,
                options = options,
            )
        val newTeam2Rating =
            smoothAndClamp(
                previous = team2PreCalculationRatingBD,
                netChange = trueTeam2RunningBD - team2PreCalculationRatingBD,
                options = options,
            )

        val team1RatingChange =
            ratingChange(
                oldRating = matchTypeHandler.getTeamPreCalculationRating(teamId = team1Id),
                newRating = newTeam1Rating,
            )
        val team2RatingChange =
            ratingChange(
                oldRating = matchTypeHandler.getTeamPreCalculationRating(teamId = team2Id),
                newRating = newTeam2Rating,
            )

        return RankingCalculationResult(
            response =
                matchTypeHandler.generateResponse(
                    newTeamRatingChanges =
                        mapOf(
                            team1Id to team1RatingChange,
                            team2Id to team2RatingChange,
                        ),
                ),
            audit = audit.getEntries(),
        )
    }

    /** Pick the format-specific handler from the request's team shape (singles = 1 player, else doubles). */
    private fun handlerFor(
        request: RankingCalculationRequest,
        audit: AuditTrail,
    ): MatchTypeHandler =
        if (request.teams.values.all { it.teamType == TeamType.SINGLES }) {
            SinglesMatchTypeHandler(request = request, auditTrail = audit)
        } else {
            DoublesMatchTypeHandler(request = request, auditTrail = audit)
        }

    /** The per-set change for one subject team, evaluated against their current in-match rating. */
    private fun stepFor(
        subjectCurrentCalculatedRating: BigDecimal,
        oppositionCurrentCalculatedRating: BigDecimal,
        set: MatchScore,
        teamId: String,
        matchTypeFactor: BigDecimal,
    ): SetStep {
        val dominance = set.calculateDominanceFactor(teamId = teamId)
        val advantage = subjectCurrentCalculatedRating - oppositionCurrentCalculatedRating
        val isWinner = set.winnerTeamId == teamId
        val normalizedGap = advantage.abs().divideBy(divisor = NTRP_RANGE)
        val isUpset = (isWinner && advantage < ZERO) || (!isWinner && advantage > ZERO)
        val scale = scaleFor(isUpset = isUpset, normalizedGap = normalizedGap) * matchTypeFactor
        val sign = if (isWinner) ONE else -ONE
        val delta = K_FACTOR_NTRP * dominance.abs() * scale * sign
        return SetStep(
            dominance = dominance,
            scale = scale,
            ratingGap = advantage.abs(),
            normalizedGap = normalizedGap,
            isUpset = isUpset,
            delta = delta,
        )
    }

    /** Scale: upsets grow with the gap (×multiplier); expected/competitive results shrink toward zero. */
    private fun scaleFor(
        isUpset: Boolean,
        normalizedGap: BigDecimal,
    ): BigDecimal =
        if (isUpset) {
            normalizedGap.divideBy(divisor = COMPETITIVE_THRESHOLD_PCT) * UPSET_MULTIPLIER
        } else {
            (COMPETITIVE_THRESHOLD_PCT - normalizedGap).divideBy(divisor = COMPETITIVE_THRESHOLD_PCT).max(ZERO)
        }

    /** Smoothing is applied once, to the whole-match net change (the only difference from N one-set matches). */
    private fun smoothAndClamp(
        previous: BigDecimal,
        netChange: BigDecimal,
        options: RatingCalculationOptions,
    ): Rating {
        val effectiveChange = if (options.smoothingEnabled) netChange * options.smoothingFactor.bd else netChange
        return clamp(value = previous + effectiveChange).asRating
    }

    private fun clamp(value: BigDecimal): BigDecimal = value.max(NTRP_MIN).min(NTRP_MAX)

    private fun ratingChange(
        oldRating: Rating,
        newRating: Rating,
    ): RatingChange {
        val previous = oldRating.value.bd
        val updated = newRating.value.bd
        // A valid NTRP rating is always in [1.0, 7.0] (Rating enforces it), so previous is never zero.
        val percentChange = ((updated - previous).divideBy(divisor = previous) * "100.0".bd).toStringPrecise()
        return RatingChange(
            change = (updated - previous).toStringPrecise(),
            previousRating = oldRating,
            newRating = newRating,
            percentChange = percentChange,
            levelChanged = oldRating.publishedLevel.value != newRating.publishedLevel.value,
        )
    }
}
