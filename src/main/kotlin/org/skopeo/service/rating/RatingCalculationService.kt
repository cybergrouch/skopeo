// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.rating

import org.jetbrains.exposed.sql.transactions.transaction
import org.skopeo.dto.RankingCalculationRequest
import org.skopeo.model.CalculationBreakdownSnapshot
import org.skopeo.model.Capability
import org.skopeo.model.Match
import org.skopeo.model.MatchScore
import org.skopeo.model.PlayerProfile
import org.skopeo.model.Rating
import org.skopeo.model.RatingCalculationOptions
import org.skopeo.model.RatingHistoryWrite
import org.skopeo.model.SetScore
import org.skopeo.model.Team
import org.skopeo.model.TeamType
import org.skopeo.model.TiebreakScore
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.calculator.AuditEntry
import org.skopeo.service.calculator.RankingCalculator
import org.skopeo.service.calculator.impl.v1.PerformanceBasedRankingCalculatorImpl
import org.skopeo.service.user.ForbiddenException
import org.skopeo.service.user.VerifiedFirebaseToken
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The rating calculation trigger (ADMINISTRATOR only). It processes the matches pending
 * calculation oldest→newest, carrying ratings forward through an in-memory snapshot so the
 * chain is correct, reusing the existing [RankingCalculator]. Dry-run (the default) returns a
 * full preview with no writes; an explicit commit persists ratings, history, and `rated_at` in
 * one transaction.
 */
class RatingCalculationService(
    private val matches: MatchRepository = MatchRepository(),
    private val ratings: RatingRepository = RatingRepository(),
    private val users: UserRepository = UserRepository(),
    private val calculator: RankingCalculator = PerformanceBasedRankingCalculatorImpl(),
) {
    /** The internal calculator derivatives behind one player's change (issue #89), as precise strings. */
    data class CalculationBreakdown(
        val dominance: String,
        val scale: String,
        val ratingGap: String,
        val normalizedGap: String,
        val competitiveThresholdPct: String,
        val isUpset: Boolean,
        val upsetMultiplier: String,
        val kFactor: String,
    )

    /** One player's computed change within a processed match. */
    data class PlayerChange(
        val userId: UUID,
        val previousRating: BigDecimal,
        val newRating: BigDecimal,
        val change: BigDecimal,
        val percentChange: BigDecimal,
        val previousLevel: String?,
        val newLevel: String?,
        val levelChanged: Boolean,
        val breakdown: CalculationBreakdown,
    )

    data class MatchCalculation(
        val matchId: UUID,
        val matchDate: LocalDate,
        val changes: List<PlayerChange>,
    )

    data class CalculationOutcome(
        val dryRun: Boolean,
        val matches: List<MatchCalculation>,
    )

    fun calculate(
        token: VerifiedFirebaseToken,
        dryRun: Boolean,
    ): CalculationOutcome {
        val adminId = requireAdmin(token = token)
        val snapshot = mutableMapOf<UUID, BigDecimal>()
        val processed = matches.listPendingCalculation().map { processMatch(match = it, snapshot = snapshot) }

        if (!dryRun) commit(processed = processed, ratedBy = adminId)
        return CalculationOutcome(dryRun = dryRun, matches = processed)
    }

    private fun commit(
        processed: List<MatchCalculation>,
        ratedBy: UUID,
    ) {
        val now = LocalDateTime.now()
        transaction {
            processed.forEach { calc ->
                calc.changes.forEach { change ->
                    ratings.applyMatchRating(
                        userId = change.userId,
                        newRating = change.newRating,
                        newLevel = change.newLevel,
                        matchDate = calc.matchDate,
                    )
                    ratings.appendHistory(
                        write =
                            RatingHistoryWrite(
                                userId = change.userId,
                                matchId = calc.matchId,
                                previousRating = change.previousRating,
                                newRating = change.newRating,
                                ratingChange = change.change,
                                percentChange = change.percentChange,
                                previousLevel = change.previousLevel,
                                newLevel = change.newLevel,
                                levelChanged = change.levelChanged,
                                breakdown = change.breakdown.toSnapshot(),
                                calculatedAt = now,
                            ),
                    )
                }
                matches.markRated(matchId = calc.matchId, ratedAt = now, ratedBy = ratedBy)
            }
        }
    }

    private fun processMatch(
        match: Match,
        snapshot: MutableMap<UUID, BigDecimal>,
    ): MatchCalculation {
        require(value = match.matchType == TeamType.SINGLES) {
            "Only SINGLES matches can be calculated currently (match ${match.id})"
        }
        val u1 = match.team1.userIds.single()
        val u2 = match.team2.userIds.single()
        val r1 = currentRating(userId = u1, snapshot = snapshot)
        val r2 = currentRating(userId = u2, snapshot = snapshot)

        val request = buildRequest(match = match, r1 = r1, r2 = r2)
        val result = calculator.calculate(request = request)
        val breakdowns = breakdownsByPlayer(audit = result.audit)

        val changes =
            listOf(
                playerChange(userId = u1, response = result.response, breakdowns = breakdowns),
                playerChange(userId = u2, response = result.response, breakdowns = breakdowns),
            )
        changes.forEach { snapshot[it.userId] = it.newRating }
        return MatchCalculation(matchId = match.id, matchDate = match.matchDate, changes = changes)
    }

    /** Pull the per-player calculator derivatives out of the audit trail, keyed by player id. */
    private fun breakdownsByPlayer(audit: List<AuditEntry>): Map<String, CalculationBreakdown> =
        audit
            .filter { it.context.containsKey(key = "playerId") && it.context.containsKey(key = "dominance") }
            .associate { entry ->
                val ctx = entry.context
                (ctx.getValue(key = "playerId") as String) to
                    CalculationBreakdown(
                        dominance = ctx.factor(key = "dominance"),
                        scale = ctx.factor(key = "scale"),
                        ratingGap = ctx.factor(key = "ratingGap"),
                        normalizedGap = ctx.factor(key = "normalizedGap"),
                        competitiveThresholdPct = ctx.factor(key = "competitiveThresholdPct"),
                        isUpset = ctx.factor(key = "isUpset").toBoolean(),
                        upsetMultiplier = ctx.factor(key = "upsetMultiplier"),
                        kFactor = ctx.factor(key = "kFactor"),
                    )
            }

    private fun currentRating(
        userId: UUID,
        snapshot: MutableMap<UUID, BigDecimal>,
    ): BigDecimal =
        snapshot.getOrPut(key = userId) {
            requireNotNull(value = ratings.findCurrentRating(userId = userId)) {
                "User $userId has no rating (pending assessment)"
            }.currentRating
        }

    private fun playerChange(
        userId: UUID,
        response: org.skopeo.dto.RankingCalculationResponse,
        breakdowns: Map<String, CalculationBreakdown>,
    ): PlayerChange {
        val rc =
            requireNotNull(value = response.ratingChanges[userId.toString()]) {
                "calculator returned no change for player $userId"
            }
        return PlayerChange(
            userId = userId,
            previousRating = BigDecimal(rc.previousRating.value),
            newRating = BigDecimal(rc.newRating.value),
            change = BigDecimal(rc.change),
            percentChange = BigDecimal(rc.percentChange.removeSuffix(suffix = "%")),
            previousLevel = rc.previousRating.publishedLevel.value,
            newLevel = rc.newRating.publishedLevel.value,
            levelChanged = rc.levelChanged,
            breakdown =
                requireNotNull(value = breakdowns[userId.toString()]) {
                    "calculator returned no breakdown for player $userId"
                },
        )
    }

    private fun requireAdmin(token: VerifiedFirebaseToken): UUID {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        if (caller == null || !caller.capabilities.contains(element = Capability.ADMINISTRATOR)) throw ForbiddenException()
        return caller.id
    }
}

/** Read an audit-context value (always a precise string for the adjustment-factor entries). */
private fun Map<String, Any>.factor(key: String): String = this.getValue(key = key) as String

/** Persist-ready form of the in-memory breakdown (#97): precise strings become [BigDecimal] columns. */
private fun RatingCalculationService.CalculationBreakdown.toSnapshot(): CalculationBreakdownSnapshot =
    CalculationBreakdownSnapshot(
        dominance = BigDecimal(dominance),
        scale = BigDecimal(scale),
        ratingGap = BigDecimal(ratingGap),
        normalizedGap = BigDecimal(normalizedGap),
        competitiveThresholdPct = BigDecimal(competitiveThresholdPct),
        isUpset = isUpset,
        upsetMultiplier = BigDecimal(upsetMultiplier),
        kFactor = BigDecimal(kFactor),
    )

private fun buildRequest(
    match: Match,
    r1: BigDecimal,
    r2: BigDecimal,
): RankingCalculationRequest {
    val u1 = match.team1.userIds.single()
    val u2 = match.team2.userIds.single()
    val t1 = match.team1.teamId.toString()
    val t2 = match.team2.teamId.toString()
    val teams =
        mapOf(
            t1 to singlesTeam(teamId = t1, userId = u1, rating = r1),
            t2 to singlesTeam(teamId = t2, userId = u2, rating = r2),
        )
    val sets =
        match.sets.map { set ->
            val tiebreak =
                if (set.tiebreakTeam1Points != null && set.tiebreakTeam2Points != null) {
                    TiebreakScore(
                        points = mapOf(t1 to set.tiebreakTeam1Points, t2 to set.tiebreakTeam2Points),
                        winnerTeamId = set.winnerTeamId.toString(),
                    )
                } else {
                    null
                }
            SetScore(
                games = mapOf(t1 to set.team1Games, t2 to set.team2Games),
                winnerTeamId = set.winnerTeamId.toString(),
                tiebreak = tiebreak,
            )
        }
    return RankingCalculationRequest(
        teams = teams,
        matchScore = MatchScore(sets = sets, winnerTeamId = match.winnerTeamId.toString()),
        matchDate = match.matchDate.toString(),
        // The occasion factor (#108) is folded into the rating change via the calculator's scale term.
        options = RatingCalculationOptions(occasionFactor = match.occasion.factor),
    )
}

private fun singlesTeam(
    teamId: String,
    userId: UUID,
    rating: BigDecimal,
): Team =
    Team(
        teamId = teamId,
        name = teamId,
        players =
            listOf(
                element =
                    PlayerProfile(
                        playerId = userId.toString(),
                        name = "Player",
                        rating = Rating.fromValue(value = rating.toPlainString()),
                    ),
            ),
        teamType = TeamType.SINGLES,
    )
