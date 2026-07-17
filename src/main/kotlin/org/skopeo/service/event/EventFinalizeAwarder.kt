// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.event

import org.skopeo.model.AwardStatus
import org.skopeo.model.Event
import org.skopeo.model.EventType
import org.skopeo.model.Match
import org.skopeo.model.MatchStatus
import org.skopeo.model.PointClass
import org.skopeo.model.PointSourceType
import org.skopeo.model.RankingPointAwardWrite
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

// The event types that carry a points budget and therefore award on finalize (#403). Every event
// class awards now that OPEN_PLAY was unified with TOURNAMENT/LEAGUE (feat/open-play-points-unify);
// a fixture pays out only if it carries a designation, which in turn requires the event to have a club.
private val AWARDING_TYPES = setOf(EventType.TOURNAMENT, EventType.LEAGUE, EventType.OPEN_PLAY)

// Validity-window thresholds (in days) that map an event's point window to the nearest existing
// PointClass (§7 + Interpretation B). ≤ 31d → 1M, ≤ 92d → 3M, ≤ 184d → 6M, else ANNUAL.
private const val ONE_MONTH_DAYS = 31L
private const val THREE_MONTH_DAYS = 92L
private const val SIX_MONTH_DAYS = 184L

private const val UNSPECIFIED_SEX = "Unspecified"

/**
 * Finalize-time points awarding (#403 Phase D, POINTS_AWARDING_AND_BUDGET.md §2.5/§3). A small
 * collaborator of [EventService.finalize] so the awarding logic stays cohesive and testable without
 * bloating EventService. On finalize of a budgeted-type event it converts each qualifying fixture's
 * designation into ledger awards — one row per winning-team member, each carrying the FULL designated
 * points (decision #4), so a team of N produces N rows of `designated` (total `designated × N`,
 * matching the Phase C reservation). Every event class awards (OPEN_PLAY unified with
 * TOURNAMENT/LEAGUE). Non-awarding paths (no winner / cancelled / no designation / winner unrated)
 * simply produce nothing (the "release").
 */
class EventFinalizeAwarder(
    private val matches: MatchRepository = MatchRepository(),
    private val awards: RankingPointRepository = RankingPointRepository(),
    private val ratings: RatingRepository = RatingRepository(),
    private val users: UserRepository = UserRepository(),
) {
    /** Summary of one finalize's awarding, for the audit trail: how many fixtures paid out and the total. */
    data class AwardSummary(
        val matchCount: Int,
        val awardCount: Int,
        val totalPoints: BigDecimal,
    )

    /**
     * Award the winners of [event]'s qualifying fixtures. A fixture qualifies when it is COMPLETED,
     * has a winner, carries a designation, and belongs to this event (decision #3). The award band is
     * the winner's CURRENT band at finalize (Interpretation A); a winner with no rating is skipped.
     * Validity + point class come from the event's window (Interpretation B). Returns an [AwardSummary].
     */
    fun awardForFinalizedEvent(
        event: Event,
        grantedBy: UUID,
        now: LocalDateTime,
    ): AwardSummary {
        val start = event.pointValidityStart
        val end = event.pointValidityEnd
        if (event.type !in AWARDING_TYPES || start == null || end == null) return emptySummary()
        val validFrom = start.atStartOfDay()
        // Exclusive end: the day after the last valid day, at start-of-day — matching the ledger's
        // [valid_from, valid_until) convention (activeAsOf uses validUntil greater asOf).
        val validUntil = end.plusDays(1).atStartOfDay()
        val pointClass = pointClassFor(start = start, end = end)

        // Each qualifying fixture already carries a non-null designation (see [qualifyingWins]); resolve
        // its winners up front so the award loop has no null-handling branches to leave uncovered.
        val wins = qualifyingWins(eventId = event.id)
        val winnerIds = wins.flatMap { it.winnerIds }.distinct()
        val bands = ratings.findCurrentRatings(userIds = winnerIds)
        val sexes = users.findAllByIds(ids = winnerIds).associate { it.id to (it.sex ?: UNSPECIFIED_SEX) }

        var matchCount = 0
        var awardCount = 0
        var total = BigDecimal.ZERO
        wins.forEach { win ->
            var paidThisMatch = false
            win.winnerIds.forEach { userId ->
                // A winner with no current rating has no band to tag — skip that award (Interpretation A).
                val band = bands[userId]?.currentLevel ?: return@forEach
                awards.award(
                    write =
                        awardWrite(
                            event = event,
                            matchId = win.matchId,
                            userId = userId,
                            designated = win.designated,
                            band = band,
                            sex = sexes.getOrDefault(key = userId, defaultValue = UNSPECIFIED_SEX),
                            pointClass = pointClass,
                            validFrom = validFrom,
                            validUntil = validUntil,
                            grantedBy = grantedBy,
                            now = now,
                        ),
                )
                awardCount += 1
                total = total.add(BigDecimal(win.designated))
                paidThisMatch = true
            }
            if (paidThisMatch) matchCount += 1
        }
        return AwardSummary(matchCount = matchCount, awardCount = awardCount, totalPoints = total)
    }

    /** One qualifying fixture reduced to what awarding needs: its id, designation, and winning members. */
    private data class QualifyingWin(
        val matchId: UUID,
        val designated: Int,
        val winnerIds: List<UUID>,
    )

    /**
     * The event's fixtures that pay out (decision #3): COMPLETED, with a winner and a designation.
     * [Match.designatedPoints] and [Match.winnerTeamId] are resolved here so the caller never re-checks
     * for null — a fixture that fails any part of the filter simply produces no [QualifyingWin].
     */
    private fun qualifyingWins(eventId: UUID): List<QualifyingWin> =
        matches
            .listByEvent(eventId = eventId)
            .filter { it.status == MatchStatus.COMPLETED }
            .mapNotNull { match ->
                val designated = match.designatedPoints ?: return@mapNotNull null
                val winnerIds = winningUserIds(match = match).ifEmpty { return@mapNotNull null }
                QualifyingWin(matchId = match.id, designated = designated, winnerIds = winnerIds)
            }

    @Suppress("LongParameterList")
    private fun awardWrite(
        event: Event,
        matchId: UUID,
        userId: UUID,
        designated: Int,
        band: String,
        sex: String,
        pointClass: PointClass,
        validFrom: LocalDateTime,
        validUntil: LocalDateTime,
        grantedBy: UUID,
        now: LocalDateTime,
    ): RankingPointAwardWrite =
        RankingPointAwardWrite(
            userId = userId,
            points = BigDecimal(designated),
            pointClass = pointClass,
            sourceType = PointSourceType.INTERNAL,
            sourceId = event.id.toString(),
            band = band,
            sex = sex,
            reason = "Awarded on finalize of event ${event.name}",
            validFrom = validFrom,
            validUntil = validUntil,
            status = AwardStatus.ACTIVE,
            revokesAwardId = null,
            grantedBy = grantedBy,
            awardedAt = now,
            eventId = event.id,
            matchId = matchId,
        )

    private fun winningUserIds(match: Match): List<UUID> =
        when (match.winnerTeamId) {
            match.team1.teamId -> match.team1.userIds
            match.team2.teamId -> match.team2.userIds
            else -> emptyList()
        }

    /** Map the event's validity-window length to the nearest existing PointClass (Interpretation B). */
    private fun pointClassFor(
        start: LocalDate,
        end: LocalDate,
    ): PointClass {
        val days = ChronoUnit.DAYS.between(start, end)
        return when {
            days <= ONE_MONTH_DAYS -> PointClass.SEASONAL_TOURNAMENT_1M
            days <= THREE_MONTH_DAYS -> PointClass.SEASONAL_TOURNAMENT_3M
            days <= SIX_MONTH_DAYS -> PointClass.SEASONAL_TOURNAMENT_6M
            else -> PointClass.ANNUAL_TOURNAMENT
        }
    }

    private fun emptySummary(): AwardSummary = AwardSummary(matchCount = 0, awardCount = 0, totalPoints = BigDecimal.ZERO)
}
