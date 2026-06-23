// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.rating

import org.jetbrains.exposed.sql.transactions.transaction
import org.skopeo.dto.RankingCalculationRequest
import org.skopeo.model.Capability
import org.skopeo.model.Match
import org.skopeo.model.MatchScore
import org.skopeo.model.PlayerProfile
import org.skopeo.model.Rating
import org.skopeo.model.RatingHistoryWrite
import org.skopeo.model.RatingSystem
import org.skopeo.model.SetScore
import org.skopeo.model.Team
import org.skopeo.model.TeamType
import org.skopeo.model.TiebreakScore
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
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
    /** One player's computed change within a processed match. */
    data class PlayerChange(
        val userId: UUID,
        val system: RatingSystem,
        val previousRating: BigDecimal,
        val newRating: BigDecimal,
        val change: BigDecimal,
        val percentChange: BigDecimal,
        val previousLevel: String?,
        val newLevel: String?,
        val levelChanged: Boolean,
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
        val adminId = requireAdmin(token)
        val snapshot = mutableMapOf<Pair<UUID, RatingSystem>, BigDecimal>()
        val processed = matches.listPendingCalculation().map { processMatch(it, snapshot) }

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
                        system = change.system,
                        newRating = change.newRating,
                        newLevel = change.newLevel,
                        matchDate = calc.matchDate,
                    )
                    ratings.appendHistory(
                        RatingHistoryWrite(
                            userId = change.userId,
                            matchId = calc.matchId,
                            system = change.system,
                            previousRating = change.previousRating,
                            newRating = change.newRating,
                            ratingChange = change.change,
                            percentChange = change.percentChange,
                            previousLevel = change.previousLevel,
                            newLevel = change.newLevel,
                            levelChanged = change.levelChanged,
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
        snapshot: MutableMap<Pair<UUID, RatingSystem>, BigDecimal>,
    ): MatchCalculation {
        require(match.matchType == TeamType.SINGLES) {
            "Only SINGLES matches can be calculated currently (match ${match.id})"
        }
        val system = match.ratingSystem
        val u1 = match.team1.userIds.single()
        val u2 = match.team2.userIds.single()
        val r1 = currentRating(u1, system, snapshot)
        val r2 = currentRating(u2, system, snapshot)

        val request = buildRequest(match = match, r1 = r1, r2 = r2)
        val response = calculator.calculate(request).response

        val changes = listOf(playerChange(u1, system, response), playerChange(u2, system, response))
        changes.forEach { snapshot[it.userId to system] = it.newRating }
        return MatchCalculation(matchId = match.id, matchDate = match.matchDate, changes = changes)
    }

    private fun currentRating(
        userId: UUID,
        system: RatingSystem,
        snapshot: MutableMap<Pair<UUID, RatingSystem>, BigDecimal>,
    ): BigDecimal =
        snapshot.getOrPut(userId to system) {
            requireNotNull(ratings.findByUserAndSystem(userId, system)) {
                "User $userId has no $system rating (pending assessment)"
            }.currentRating
        }

    private fun playerChange(
        userId: UUID,
        system: RatingSystem,
        response: org.skopeo.dto.RankingCalculationResponse,
    ): PlayerChange {
        val rc =
            requireNotNull(response.ratingChanges[userId.toString()]) {
                "calculator returned no change for player $userId"
            }
        return PlayerChange(
            userId = userId,
            system = system,
            previousRating = BigDecimal(rc.previousRating.value),
            newRating = BigDecimal(rc.newRating.value),
            change = BigDecimal(rc.change),
            percentChange = BigDecimal(rc.percentChange.removeSuffix("%")),
            previousLevel = rc.previousRating.publishedLevel.value,
            newLevel = rc.newRating.publishedLevel.value,
            levelChanged = rc.levelChanged,
        )
    }

    private fun requireAdmin(token: VerifiedFirebaseToken): UUID {
        val caller = users.findByFirebaseUid(token.uid)
        if (caller == null || !caller.capabilities.contains(Capability.ADMINISTRATOR)) throw ForbiddenException()
        return caller.id
    }
}

private fun buildRequest(
    match: Match,
    r1: BigDecimal,
    r2: BigDecimal,
): RankingCalculationRequest {
    val system = match.ratingSystem
    val u1 = match.team1.userIds.single()
    val u2 = match.team2.userIds.single()
    val t1 = match.team1.teamId.toString()
    val t2 = match.team2.teamId.toString()
    val teams =
        mapOf(
            t1 to singlesTeam(t1, u1, r1, system),
            t2 to singlesTeam(t2, u2, r2, system),
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
        matchScore = MatchScore(sets = sets, winnerTeamId = match.winnerTeamId.toString(), matchFormat = match.matchFormat),
        matchDate = match.matchDate.toString(),
    )
}

private fun singlesTeam(
    teamId: String,
    userId: UUID,
    rating: BigDecimal,
    system: RatingSystem,
): Team =
    Team(
        teamId = teamId,
        name = teamId,
        players =
            listOf(
                PlayerProfile(playerId = userId.toString(), name = "Player", rating = Rating.fromValue(rating.toPlainString(), system)),
            ),
        teamType = TeamType.SINGLES,
    )
