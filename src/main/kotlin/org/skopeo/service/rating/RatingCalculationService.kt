// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.rating

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
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
import org.skopeo.model.ServiceError
import org.skopeo.model.SetCalculationBreakdown
import org.skopeo.model.SetScore
import org.skopeo.model.Team
import org.skopeo.model.TeamType
import org.skopeo.model.TiebreakScore
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.calculator.AuditEntry
import org.skopeo.service.calculator.RankingCalculator
import org.skopeo.service.calculator.impl.v2.PerformanceBasedRankingCalculatorImpl
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
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
class RatingCalculationService(
    private val matches: MatchRepository = MatchRepository(),
    private val ratings: RatingRepository = RatingRepository(),
    private val users: UserRepository = UserRepository(),
    private val calculator: RankingCalculator = PerformanceBasedRankingCalculatorImpl(),
) {
    /**
     * The internal calculator derivatives behind one player's change (issue #89), as precise strings.
     * v1 fills the net fields and leaves [sets] empty; v2 leaves the net fields null and fills [sets] (#110).
     */
    data class CalculationBreakdown(
        val dominance: String?,
        val scale: String?,
        val ratingGap: String?,
        val normalizedGap: String?,
        val competitiveThresholdPct: String?,
        val isUpset: Boolean?,
        val upsetMultiplier: String?,
        val kFactor: String?,
        val sets: List<SetCalculationBreakdown> = emptyList(),
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
    ): Either<ServiceError, CalculationOutcome> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val snapshot = mutableMapOf<UUID, BigDecimal>()
            val processed = matches.listPendingCalculation().map { processMatch(match = it, snapshot = snapshot).bind() }

            if (!dryRun) commit(processed = processed, ratedBy = adminId)
            CalculationOutcome(dryRun = dryRun, matches = processed)
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
    ): Either<ServiceError, MatchCalculation> =
        either {
            ensure(condition = match.matchFormat == TeamType.SINGLES) {
                ServiceError.Validation(message = "Only SINGLES matches can be calculated currently (match ${match.id})")
            }
            val u1 = match.team1.userIds.single()
            val u2 = match.team2.userIds.single()
            val r1 = currentRating(userId = u1, snapshot = snapshot).bind()
            val r2 = currentRating(userId = u2, snapshot = snapshot).bind()

            val request = buildRequest(match = match, r1 = r1, r2 = r2)
            val result = calculator.calculate(request = request)
            val breakdowns = breakdownsByPlayer(audit = result.audit)

            val changes =
                listOf(
                    playerChange(userId = u1, response = result.response, breakdowns = breakdowns).bind(),
                    playerChange(userId = u2, response = result.response, breakdowns = breakdowns).bind(),
                )
            changes.forEach { snapshot[it.userId] = it.newRating }
            MatchCalculation(matchId = match.id, matchDate = match.matchDate, changes = changes)
        }

    /**
     * Pull the per-player calculator derivatives out of the audit trail, keyed by player id. v1 emits
     * one net entry per player (no `setIndex`); v2 emits one entry per set per player (with `setIndex`,
     * #110). Per-set entries are grouped into an ordered [SetCalculationBreakdown] list with the net
     * fields left null; net entries keep the existing net breakdown with no sets.
     */
    private fun breakdownsByPlayer(audit: List<AuditEntry>): Map<String, CalculationBreakdown> {
        // Every breakdown entry (v1 net or v2 per-set) carries a "dominance" key alongside "playerId";
        // match-level audit entries carry neither. Filtering on the one key avoids a permanently dead
        // "playerId without dominance" branch (the two keys are always emitted together).
        val relevant = audit.filter { it.context.containsKey(key = "dominance") }
        val (perSet, net) = relevant.partition { it.context.containsKey(key = "setIndex") }

        val perSetByPlayer =
            perSet
                .groupBy { it.context.getValue(key = "playerId") as String }
                .mapValues { (_, entries) ->
                    val steps =
                        entries
                            .sortedBy { it.context.factor(key = "setIndex").toInt() }
                            .map { it.context.toSetBreakdown() }
                    CalculationBreakdown(
                        dominance = null,
                        scale = null,
                        ratingGap = null,
                        normalizedGap = null,
                        competitiveThresholdPct = null,
                        isUpset = null,
                        upsetMultiplier = null,
                        kFactor = null,
                        sets = steps,
                    )
                }

        val netByPlayer =
            net.associate { entry ->
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

        return netByPlayer + perSetByPlayer
    }

    private fun currentRating(
        userId: UUID,
        snapshot: MutableMap<UUID, BigDecimal>,
    ): Either<ServiceError, BigDecimal> =
        either {
            snapshot.getOrElse(key = userId) {
                val rating = ratings.findCurrentRating(userId = userId)
                ensureNotNull(value = rating) {
                    ServiceError.Validation(message = "User $userId has no rating (pending assessment)")
                }
                rating.currentRating.also { snapshot[userId] = it }
            }
        }

    private fun playerChange(
        userId: UUID,
        response: org.skopeo.dto.RankingCalculationResponse,
        breakdowns: Map<String, CalculationBreakdown>,
    ): Either<ServiceError, PlayerChange> =
        either {
            val rc = response.ratingChanges[userId.toString()]
            ensureNotNull(value = rc) {
                ServiceError.Validation(message = "calculator returned no change for player $userId")
            }
            val breakdown = breakdowns[userId.toString()]
            ensureNotNull(value = breakdown) {
                ServiceError.Validation(message = "calculator returned no breakdown for player $userId")
            }
            PlayerChange(
                userId = userId,
                previousRating = BigDecimal(rc.previousRating.value),
                newRating = BigDecimal(rc.newRating.value),
                change = BigDecimal(rc.change),
                percentChange = BigDecimal(rc.percentChange.removeSuffix(suffix = "%")),
                previousLevel = rc.previousRating.publishedLevel.value,
                newLevel = rc.newRating.publishedLevel.value,
                levelChanged = rc.levelChanged,
                breakdown = breakdown,
            )
        }

    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        return if (caller == null || !caller.capabilities.contains(element = Capability.ADMINISTRATOR)) {
            ServiceError.Forbidden().left()
        } else {
            caller.id.right()
        }
    }
}

/** Read an audit-context value (always a precise string for the adjustment-factor entries). */
private fun Map<String, Any>.factor(key: String): String = this.getValue(key = key) as String

/** Map a v2 per-set audit-context map (#110) into a [SetCalculationBreakdown]. */
private fun Map<String, Any>.toSetBreakdown(): SetCalculationBreakdown =
    SetCalculationBreakdown(
        setIndex = factor(key = "setIndex").toInt(),
        score = factor(key = "setScore"),
        dominance = factor(key = "dominance"),
        scale = factor(key = "scale"),
        ratingGap = factor(key = "ratingGap"),
        normalizedGap = factor(key = "normalizedGap"),
        competitiveThresholdPct = factor(key = "competitiveThresholdPct"),
        isUpset = factor(key = "isUpset").toBoolean(),
        upsetMultiplier = factor(key = "upsetMultiplier"),
        kFactor = factor(key = "kFactor"),
        delta = factor(key = "delta"),
        ratingAfter = factor(key = "ratingAfter"),
    )

/** Persist-ready form of the in-memory breakdown (#97/#110): net strings become [BigDecimal] columns, sets carry through. */
private fun RatingCalculationService.CalculationBreakdown.toSnapshot(): CalculationBreakdownSnapshot =
    CalculationBreakdownSnapshot(
        dominance = dominance?.let { BigDecimal(it) },
        scale = scale?.let { BigDecimal(it) },
        ratingGap = ratingGap?.let { BigDecimal(it) },
        normalizedGap = normalizedGap?.let { BigDecimal(it) },
        competitiveThresholdPct = competitiveThresholdPct?.let { BigDecimal(it) },
        isUpset = isUpset,
        upsetMultiplier = upsetMultiplier?.let { BigDecimal(it) },
        kFactor = kFactor?.let { BigDecimal(it) },
        sets = sets,
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
            val winner = set.winnerTeamId.toString()
            val games = mapOf(t1 to set.team1Games, t2 to set.team2Games)
            SetScore(
                games = games,
                winnerTeamId = winner,
                // Name the loser explicitly (the other team): for an equal-games set (tiebreak-decided)
                // the default (fewest games) would otherwise collide with the winner.
                loserTeamId = (games.keys - winner).single(),
                tiebreak = tiebreak,
            )
        }
    return RankingCalculationRequest(
        teams = teams,
        matchScore = MatchScore(sets = sets, winnerTeamId = match.winnerTeamId.toString()),
        matchDate = match.matchDate.toString(),
        // The match-type factor (#108) is folded into the rating change via the calculator's scale term.
        options = RatingCalculationOptions(matchTypeFactor = match.matchType.factor),
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
