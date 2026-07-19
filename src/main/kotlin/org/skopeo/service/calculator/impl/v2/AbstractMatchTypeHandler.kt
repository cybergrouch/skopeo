// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.calculator.impl.v2

import org.skopeo.dto.RankingCalculationRequest
import org.skopeo.model.PlayerProfile
import org.skopeo.model.SetScore
import org.skopeo.model.bd
import org.skopeo.model.toStringPrecise
import org.skopeo.service.calculator.AuditEntry
import org.skopeo.service.calculator.AuditTrail
import org.skopeo.service.calculator.impl.v2.PerformanceBasedRankingCalculatorImpl.Companion.COMPETITIVE_THRESHOLD_PCT
import org.skopeo.service.calculator.impl.v2.PerformanceBasedRankingCalculatorImpl.Companion.K_FACTOR_NTRP
import org.skopeo.service.calculator.impl.v2.PerformanceBasedRankingCalculatorImpl.Companion.NTRP_MIN
import org.skopeo.service.calculator.impl.v2.PerformanceBasedRankingCalculatorImpl.Companion.UPSET_MULTIPLIER
import org.skopeo.service.calculator.impl.v2.PerformanceBasedRankingCalculatorImpl.SetStep
import java.math.BigDecimal

/**
 * Shared audit-recording flow for the v2 handlers. The per-set audit reads the same for every format —
 * the team-level dominance/scale/gap/upset factors, the player's delta, and their rating afterwards — so
 * the only thing that varies is *which players a team's step maps onto and by how much*. Subclasses supply
 * that via [resolvePlayerSteps] (singles → the one player takes the whole step; doubles → the step is split
 * across the two partners), and this class turns each result into an [AuditEntry].
 */
abstract class AbstractMatchTypeHandler(
    protected val request: RankingCalculationRequest,
    private val auditTrail: AuditTrail,
) : MatchTypeHandler {
    /** One player's outcome for a single set: the delta applied and the rating it leaves them at. */
    protected data class PlayerSetResult(
        val player: PlayerProfile,
        val delta: BigDecimal,
        val ratingAfter: BigDecimal,
    )

    /** Suffix after the player name in the audit message (e.g. " (doubles)"); blank for singles. */
    protected open val auditLabel: String get() = ""

    protected fun playersOf(teamId: String): List<PlayerProfile> = request.teams.getValue(key = teamId).players

    /**
     * True pre-calculation rating minus this side's handicap (issue #486), floored at the NTRP minimum
     * (1.0). Shared by both formats: singles deducts from the single player's rating, doubles from the
     * side mean. With no handicap the effective rating equals the true one.
     */
    final override fun getTeamEffectiveRating(teamId: String): BigDecimal {
        val truePreCalc = getTeamPreCalculationRating(teamId = teamId).value.bd
        val handicap = request.teams.getValue(key = teamId).handicap?.bd ?: return truePreCalc
        return (truePreCalc - handicap).max(NTRP_MIN)
    }

    /**
     * How this format maps a team's per-set [step] onto its players. [teamRatingAfter] is the team's
     * post-set calculated rating (used directly by singles; doubles carries its own per-partner running rating).
     */
    protected abstract fun resolvePlayerSteps(
        teamId: String,
        step: SetStep,
        teamRatingAfter: BigDecimal,
    ): List<PlayerSetResult>

    final override fun createSetCalculationAudit(
        teamId: String,
        setScore: SetScore,
        index: Int,
        step: SetStep,
        ratingAfter: BigDecimal,
    ) {
        val self = setScore.games.getValue(key = teamId)
        val opponent = setScore.games.filterKeys { it != teamId }.values.sum()
        val scoreLabel = "$self-$opponent"
        // Issue #486: the side's applied handicap, recorded so the breakdown stays interpretable — the
        // dominance/gap/scale reflect the *handicapped* gap while previous/new ratings are true.
        val appliedHandicap = request.teams.getValue(key = teamId).handicap
        resolvePlayerSteps(teamId = teamId, step = step, teamRatingAfter = ratingAfter).forEach { result ->
            auditTrail.add(
                entry =
                    auditEntry(
                        result = result,
                        scoreLabel = scoreLabel,
                        index = index,
                        step = step,
                        appliedHandicap = appliedHandicap,
                    ),
            )
        }
    }

    private fun auditEntry(
        result: PlayerSetResult,
        scoreLabel: String,
        index: Int,
        step: SetStep,
        appliedHandicap: String?,
    ): AuditEntry {
        val delta = result.delta.toStringPrecise()
        val ratingAfter = result.ratingAfter.toStringPrecise()
        // The dominance/scale/gap/upset factors are the team's; delta is this player's share, so
        // RatingCalculationService builds correct per-player history. When a handicap (#486) applied, an
        // extra "appliedHandicap" entry keeps the (handicapped) gap fields interpretable vs true ratings.
        val baseContext =
            mapOf(
                "playerId" to result.player.playerId,
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
                "delta" to delta,
                "ratingAfter" to ratingAfter,
            )
        return AuditEntry(
            message =
                "Set ${index + 1} - ${result.player.name}$auditLabel: $scoreLabel, " +
                    "dominance ${step.dominance.toStringPrecise()}, scale ${step.scale.toStringPrecise()} -> Δ $delta, rating $ratingAfter",
            context = if (appliedHandicap == null) baseContext else baseContext + ("appliedHandicap" to appliedHandicap),
        )
    }
}
