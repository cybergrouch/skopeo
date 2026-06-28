// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.calculator.impl.v2

import org.skopeo.dto.RankingCalculationRequest
import org.skopeo.dto.RankingCalculationResponse
import org.skopeo.dto.RatingChange
import org.skopeo.model.MatchScore
import org.skopeo.model.PlayerProfile
import org.skopeo.model.Rating
import org.skopeo.model.RatingCalculationOptions
import org.skopeo.model.SetScore
import org.skopeo.model.bd
import org.skopeo.model.calculateDominanceFactor
import org.skopeo.model.divideBy
import org.skopeo.model.toStringPrecise
import org.skopeo.service.calculator.AuditEntry
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
    companion object {
        private val ZERO = "0.0".bd
        private val ONE = "1.0".bd
        private val NTRP_MIN = "1.0".bd
        private val NTRP_MAX = "7.0".bd
        private val NTRP_RANGE = NTRP_MAX - NTRP_MIN // 6.0
        private val K_FACTOR_NTRP = "0.16".bd
        private val COMPETITIVE_THRESHOLD_PCT = "0.083".bd // 0.5 NTRP / 6.0 range
        private val UPSET_MULTIPLIER = "2.0".bd
    }

    /** One player's computed step for a single set, against their current in-match rating. */
    private data class SetStep(
        val dominance: BigDecimal,
        val scale: BigDecimal,
        val ratingGap: BigDecimal,
        val normalizedGap: BigDecimal,
        val isUpset: Boolean,
        val delta: BigDecimal,
    )

    override fun calculate(request: RankingCalculationRequest): RankingCalculationResult {
        val audit = AuditTrail()
        val teamIds = request.teams.keys.toList()
        val team1Id = teamIds[0]
        val team2Id = teamIds[1]
        val player1 = request.teams.getValue(key = team1Id).getSinglePlayer()
        val player2 = request.teams.getValue(key = team2Id).getSinglePlayer()
        val options = request.options ?: RatingCalculationOptions()
        val matchTypeFactor = options.matchTypeFactor.bd

        val pre1 = player1.rating.value.bd
        val pre2 = player2.rating.value.bd
        var current1 = pre1
        var current2 = pre2

        request.matchScore.sets.forEachIndexed { index, set ->
            val setScore = MatchScore(sets = listOf(element = set))
            val step1 = stepFor(self = current1, opponent = current2, set = setScore, teamId = team1Id, matchTypeFactor = matchTypeFactor)
            val step2 = stepFor(self = current2, opponent = current1, set = setScore, teamId = team2Id, matchTypeFactor = matchTypeFactor)
            current1 = clamp(value = current1 + step1.delta)
            current2 = clamp(value = current2 + step2.delta)
            audit.add(entry = setAudit(player = player1, teamId = team1Id, set = set, index = index, step = step1, ratingAfter = current1))
            audit.add(entry = setAudit(player = player2, teamId = team2Id, set = set, index = index, step = step2, ratingAfter = current2))
        }

        val new1 = smoothAndClamp(previous = pre1, netChange = current1 - pre1, options = options)
        val new2 = smoothAndClamp(previous = pre2, netChange = current2 - pre2, options = options)

        val response =
            RankingCalculationResponse(
                ratingChanges =
                    mapOf(
                        player1.playerId to ratingChange(player = player1, newRating = new1),
                        player2.playerId to ratingChange(player = player2, newRating = new2),
                    ),
                players =
                    mapOf(
                        player1.playerId to player1.copy(rating = new1),
                        player2.playerId to player2.copy(rating = new2),
                    ),
                teams =
                    mapOf(
                        team1Id to request.teams.getValue(key = team1Id).copy(players = listOf(element = player1.copy(rating = new1))),
                        team2Id to request.teams.getValue(key = team2Id).copy(players = listOf(element = player2.copy(rating = new2))),
                    ),
            )
        return RankingCalculationResult(response = response, audit = audit.getEntries())
    }

    /** The per-set change for one player, evaluated against their current in-match rating. */
    private fun stepFor(
        self: BigDecimal,
        opponent: BigDecimal,
        set: MatchScore,
        teamId: String,
        matchTypeFactor: BigDecimal,
    ): SetStep {
        val dominance = set.calculateDominanceFactor(teamId = teamId)
        val advantage = self - opponent
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
        return Rating.fromValue(value = clamp(value = previous + effectiveChange).toStringPrecise())
    }

    private fun clamp(value: BigDecimal): BigDecimal = value.max(NTRP_MIN).min(NTRP_MAX)

    private fun ratingChange(
        player: PlayerProfile,
        newRating: Rating,
    ): RatingChange {
        val previous = player.rating.value.bd
        val updated = newRating.value.bd
        val percentChange =
            if (previous == ZERO) {
                "0.000000"
            } else {
                ((updated - previous).divideBy(divisor = previous) * "100.0".bd).toStringPrecise()
            }
        return RatingChange(
            change = (updated - previous).toStringPrecise(),
            previousRating = player.rating,
            newRating = newRating,
            percentChange = percentChange,
            levelChanged = player.rating.publishedLevel.value != newRating.publishedLevel.value,
        )
    }

    private fun setAudit(
        player: PlayerProfile,
        teamId: String,
        set: SetScore,
        index: Int,
        step: SetStep,
        ratingAfter: BigDecimal,
    ): AuditEntry {
        val self = set.games[teamId] ?: 0
        val opponent = set.games.filterKeys { it != teamId }.values.sum()
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
