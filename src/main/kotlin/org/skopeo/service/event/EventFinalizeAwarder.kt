// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.event

import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.AwardStatus
import org.skopeo.model.Event
import org.skopeo.model.EventType
import org.skopeo.model.Level
import org.skopeo.model.Match
import org.skopeo.model.MatchSide
import org.skopeo.model.MatchStatus
import org.skopeo.model.PointClass
import org.skopeo.model.PointSourceType
import org.skopeo.model.RankingPointAwardWrite
import org.skopeo.model.UserRating
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

// Only TOURNAMENT (host-designated points) and OPEN_PLAY (computed per-set points, #525) award on
// finalize; LEAGUE is intentionally excluded for now (its points model is deferred to a follow-up
// issue), so finalizing a league event awards nothing. See the `when` in [awardForFinalizedEvent].

// Validity-window thresholds (in days) that map an event's point window to the nearest existing
// PointClass (§7 + Interpretation B). ≤ 31d → 1M, ≤ 92d → 3M, ≤ 184d → 6M, else ANNUAL.
private const val ONE_MONTH_DAYS = 31L
private const val THREE_MONTH_DAYS = 92L
private const val SIX_MONTH_DAYS = 184L

private const val UNSPECIFIED_SEX = "Unspecified"

// Open-play validity defaults to a window starting at the event's end date and running this many
// months (#525) when the organizer has not set an explicit points-validity window on the event.
private const val OPEN_PLAY_VALIDITY_MONTHS = 2L
private const val BAND_MEAN_SCALE = 4

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
    private val audit: AuditService = AuditService(),
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
    ): AwardSummary =
        when (event.type) {
            EventType.OPEN_PLAY -> awardComputedOpenPlay(event = event, grantedBy = grantedBy, now = now)
            EventType.TOURNAMENT -> awardDesignated(event = event, grantedBy = grantedBy, now = now)
            // LEAGUE and any future type: no awarding yet (#525).
            else -> AwardSummary(matchCount = 0, awardCount = 0, totalPoints = BigDecimal.ZERO)
        }

    /**
     * Host-designated awarding (TOURNAMENT): one full-designation row per winning-team member, band =
     * the winner's current band at finalize, validity + point class from the event's window.
     */
    private fun awardDesignated(
        event: Event,
        grantedBy: UUID,
        now: LocalDateTime,
    ): AwardSummary {
        val start = event.pointValidityStart
        val end = event.pointValidityEnd
        if (start == null || end == null) return AwardSummary(matchCount = 0, awardCount = 0, totalPoints = BigDecimal.ZERO)
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
                            points = BigDecimal(win.designated),
                            band = band,
                            sex = sexes.getOrDefault(key = userId, defaultValue = UNSPECIFIED_SEX),
                            pointClass = pointClass,
                            validFrom = validFrom,
                            validUntil = validUntil,
                            grantedBy = grantedBy,
                            now = now,
                        ),
                )
                // Per-award audit (#471): one entry targeting the awarded player (USER) so the reward is
                // individually auditable in the Activity Log with the player as the Target — the actor is
                // the finalizer (grantedBy). This is in addition to EventService's event-level summary.
                audit.record(
                    write =
                        pointsAudit(
                            event = event,
                            matchId = win.matchId,
                            matchPublicCode = win.matchPublicCode,
                            userId = userId,
                            points = win.designated,
                            band = band,
                            grantedBy = grantedBy,
                            validFrom = validFrom,
                            validUntil = validUntil,
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

    /**
     * Computed open-play awarding (#525). Each completed fixture is scored per set from the two teams'
     * ENTRY bands (their current band at finalize — the event's own matches are not rated until after
     * finalize, so current == entry) and paid to EVERY participant on both sides, winner and loser,
     * including zero and negative totals. Each row is tagged with the recipient's own band + sex. The
     * validity window is the event's if set, else defaults to [event end, end + 2 months).
     */
    private fun awardComputedOpenPlay(
        event: Event,
        grantedBy: UUID,
        now: LocalDateTime,
    ): AwardSummary {
        val validFrom = (event.pointValidityStart ?: event.endDate).atStartOfDay()
        val validUntil = (event.pointValidityEnd ?: event.endDate.plusMonths(OPEN_PLAY_VALIDITY_MONTHS)).plusDays(1).atStartOfDay()
        val completed =
            matches.listByEvent(eventId = event.id).filter { it.status == MatchStatus.COMPLETED && it.winnerTeamId != null }
        val userIds = completed.flatMap { it.team1.userIds + it.team2.userIds }.distinct()
        val bands = ratings.findCurrentRatings(userIds = userIds)
        val sexes = users.findAllByIds(ids = userIds).associate { it.id to (it.sex ?: UNSPECIFIED_SEX) }
        val ctx =
            OpenPlayContext(
                bands = bands,
                sexes = sexes,
                grantedBy = grantedBy,
                now = now,
                validFrom = validFrom,
                validUntil = validUntil,
            )

        var matchCount = 0
        var awardCount = 0
        var total = BigDecimal.ZERO
        completed.forEach { match ->
            val band1 = teamBand(userIds = match.team1.userIds, bands = bands) ?: return@forEach
            val band2 = teamBand(userIds = match.team2.userIds, bands = bands) ?: return@forEach
            val points =
                OpenPlayPointsCalculator.compute(
                    band1 = band1,
                    band2 = band2,
                    team1Id = match.team1.teamId,
                    sets = match.sets,
                )
            awardCount += awardSide(event = event, match = match, side = match.team1, teamPoints = points.team1, ctx = ctx)
            awardCount += awardSide(event = event, match = match, side = match.team2, teamPoints = points.team2, ctx = ctx)
            total =
                total.add(BigDecimal(points.team1 * match.team1.userIds.size + points.team2 * match.team2.userIds.size))
            matchCount += 1
        }
        return AwardSummary(matchCount = matchCount, awardCount = awardCount, totalPoints = total)
    }

    /** The finalize-time invariants shared by every open-play award row (bundled to keep signatures small). */
    private data class OpenPlayContext(
        val bands: Map<UUID, UserRating>,
        val sexes: Map<UUID, String>,
        val grantedBy: UUID,
        val now: LocalDateTime,
        val validFrom: LocalDateTime,
        val validUntil: LocalDateTime,
    )

    /** Award [teamPoints] to every member of [side], each tagged with their own band + sex. Returns rows written. */
    private fun awardSide(
        event: Event,
        match: Match,
        side: MatchSide,
        teamPoints: Int,
        ctx: OpenPlayContext,
    ): Int {
        var written = 0
        side.userIds.forEach { userId ->
            val band = ctx.bands[userId]?.currentLevel ?: return@forEach
            awards.award(
                write =
                    awardWrite(
                        event = event,
                        matchId = match.id,
                        userId = userId,
                        points = BigDecimal(teamPoints),
                        band = band,
                        sex = ctx.sexes.getOrDefault(key = userId, defaultValue = UNSPECIFIED_SEX),
                        pointClass = PointClass.OPEN_PLAY,
                        validFrom = ctx.validFrom,
                        validUntil = ctx.validUntil,
                        grantedBy = ctx.grantedBy,
                        now = ctx.now,
                    ),
            )
            audit.record(
                write =
                    pointsAudit(
                        event = event,
                        matchId = match.id,
                        matchPublicCode = match.publicCode,
                        userId = userId,
                        points = teamPoints,
                        band = band,
                        grantedBy = ctx.grantedBy,
                        validFrom = ctx.validFrom,
                        validUntil = ctx.validUntil,
                    ),
            )
            written += 1
        }
        return written
    }

    /** The team's entry band = band of the mean of members' current ratings; null if any member is unrated. */
    private fun teamBand(
        userIds: List<UUID>,
        bands: Map<UUID, UserRating>,
    ): String? {
        val ratingValues = userIds.mapNotNull { bands[it]?.currentRating }
        if (userIds.isEmpty() || ratingValues.size != userIds.size) return null
        val mean = ratingValues.reduce { a, b -> a.add(b) }.divide(BigDecimal(userIds.size), BAND_MEAN_SCALE, RoundingMode.HALF_UP)
        return Level.fromValue(value = mean.toPlainString()).value
    }

    /** One qualifying fixture reduced to what awarding needs: its id, public code, designation, and winning members. */
    private data class QualifyingWin(
        val matchId: UUID,
        val matchPublicCode: String,
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
                QualifyingWin(
                    matchId = match.id,
                    matchPublicCode = match.publicCode,
                    designated = designated,
                    winnerIds = winnerIds,
                )
            }

    @Suppress("LongParameterList")
    private fun awardWrite(
        event: Event,
        matchId: UUID,
        userId: UUID,
        points: BigDecimal,
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
            points = points,
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

    /**
     * The per-award provenance record (#471): actor = the finalizer, target = the awarded player (USER)
     * so the Activity Log's Target column links to the player. Details carry the points, granting match
     * (id + public code), event, band, and the validity window.
     */
    @Suppress("LongParameterList")
    private fun pointsAudit(
        event: Event,
        matchId: UUID,
        matchPublicCode: String,
        userId: UUID,
        points: Int,
        band: String,
        grantedBy: UUID,
        validFrom: LocalDateTime,
        validUntil: LocalDateTime,
    ): AuditWrite =
        AuditWrite(
            actorUserId = grantedBy,
            action = AuditAction.RANKING_POINTS_AWARDED,
            entityType = AuditEntityType.USER,
            entityId = userId,
            summary =
                "Awarded $points points to the player for match $matchPublicCode",
            details =
                mapOf(
                    "points" to points.toString(),
                    "matchId" to matchId.toString(),
                    "matchPublicCode" to matchPublicCode,
                    "eventId" to event.id.toString(),
                    "eventPublicCode" to event.publicCode,
                    "band" to band,
                    "validFrom" to validFrom.toString(),
                    "validUntil" to validUntil.toString(),
                ),
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
}
