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
import org.skopeo.model.PlacementBracket
import org.skopeo.model.PointClass
import org.skopeo.model.PointSourceType
import org.skopeo.model.RankingPointAwardWrite
import org.skopeo.model.UserRating
import org.skopeo.repository.ClubRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.util.UUID

// Only TOURNAMENT (host-designated points) and OPEN_PLAY (computed per-set points, #525) award on
// finalize; LEAGUE is intentionally excluded for now (its points model is deferred to a follow-up
// issue), so finalizing a league event awards nothing. See the `when` in [awardForFinalizedEvent].

private const val UNSPECIFIED_SEX = "Unspecified"

// Open-play validity defaults to a window starting at the event's end date and running this many
// months (#525) when the organizer has not set an explicit points-validity window on the event.
private const val OPEN_PLAY_VALIDITY_MONTHS = 2L
private const val BAND_MEAN_SCALE = 4

// Tournament placement points (#525), a configurable schedule indexed by finishing place (1st..4th).
// Sanctioned clubs use the full table; unsanctioned clubs (or a tournament with no club) use half.
@Suppress("MagicNumber") // the published placement points schedule
private val SANCTIONED_PLACEMENT_POINTS = listOf(80, 60, 40, 30)

@Suppress("MagicNumber") // the published placement points schedule (unsanctioned = half)
private val UNSANCTIONED_PLACEMENT_POINTS = listOf(40, 30, 20, 15)

// Zero-based indices into the placement schedule for each finishing place.
private const val PLACE_FIRST = 0
private const val PLACE_SECOND = 1
private const val PLACE_THIRD = 2
private const val PLACE_FOURTH = 3

// Tournament points default to a 12-month validity when the organizer set no explicit window (#525).
private const val TOURNAMENT_VALIDITY_MONTHS = 12L

/**
 * Finalize-time points awarding (#403 Phase D; open-play + tournament model #525). A small
 * collaborator of [EventService.finalize] so the awarding logic stays cohesive and testable without
 * bloating EventService. Awarding is by event type: OPEN_PLAY is computed per set from band
 * difference (both winner and loser); TOURNAMENT pays placement points from designated placement
 * matches (Super/Plate Finals), sanction-selected via the event's club; LEAGUE awards nothing for
 * now. A winner/participant with no current rating has no band to tag and is skipped.
 */
class EventFinalizeAwarder(
    private val matches: MatchRepository = MatchRepository(),
    private val awards: RankingPointRepository = RankingPointRepository(),
    private val ratings: RatingRepository = RatingRepository(),
    private val users: UserRepository = UserRepository(),
    private val clubs: ClubRepository = ClubRepository(),
    private val audit: AuditService = AuditService(),
) {
    /** Summary of one finalize's awarding, for the audit trail: how many fixtures paid out and the total. */
    data class AwardSummary(
        val matchCount: Int,
        val awardCount: Int,
        val totalPoints: BigDecimal,
    )

    /** Award [event]'s finalized fixtures by type (see class doc). Returns an [AwardSummary]. */
    fun awardForFinalizedEvent(
        event: Event,
        grantedBy: UUID,
        now: LocalDateTime,
    ): AwardSummary =
        when (event.type) {
            EventType.OPEN_PLAY -> awardComputedOpenPlay(event = event, grantedBy = grantedBy, now = now)
            EventType.TOURNAMENT -> awardPlacement(event = event, grantedBy = grantedBy, now = now)
            // LEAGUE and any future type: no awarding yet (#525).
            else -> AwardSummary(matchCount = 0, awardCount = 0, totalPoints = BigDecimal.ZERO)
        }

    /**
     * Placement-based tournament awarding (#525). Each COMPLETED placement match (Super Finals →
     * 1st/2nd, Plate Finals → 3rd/4th) pays its winner and loser from the sanction-selected schedule
     * — the full table if the event's club has tournaments sanctioned, else the halved table (a
     * tournament with no club is unsanctioned). One full-amount row per team member (doubles: both
     * partners), tagged with each recipient's current band + sex. Regular fixtures award nothing.
     */
    private fun awardPlacement(
        event: Event,
        grantedBy: UUID,
        now: LocalDateTime,
    ): AwardSummary {
        val sanctioned = event.clubId?.let { clubs.findById(id = it)?.tournamentsSanctioned } ?: false
        val schedule = if (sanctioned) SANCTIONED_PLACEMENT_POINTS else UNSANCTIONED_PLACEMENT_POINTS
        val start = event.pointValidityStart ?: event.endDate
        val end = event.pointValidityEnd ?: event.endDate.plusMonths(TOURNAMENT_VALIDITY_MONTHS)
        val placementMatches = matches.listByEvent(eventId = event.id).filter { isAwardablePlacement(match = it) }
        val userIds = placementMatches.flatMap { it.team1.userIds + it.team2.userIds }.distinct()
        val ctx =
            AwardContext(
                bands = ratings.findCurrentRatings(userIds = userIds),
                sexes = users.findAllByIds(ids = userIds).associate { it.id to (it.sex ?: UNSPECIFIED_SEX) },
                grantedBy = grantedBy,
                now = now,
                validFrom = start.atStartOfDay(),
                validUntil = end.plusDays(1).atStartOfDay(),
            )

        var matchCount = 0
        var awardCount = 0
        var total = BigDecimal.ZERO
        placementMatches.forEach { match ->
            val bracket = match.placementBracket ?: return@forEach
            val (winnerIdx, loserIdx) = placeIndices(bracket = bracket)
            val team1Won = match.winnerTeamId == match.team1.teamId
            val winnerSide = if (team1Won) match.team1 else match.team2
            val loserSide = if (team1Won) match.team2 else match.team1
            val rows =
                awardPlacementSide(event = event, match = match, side = winnerSide, placePoints = schedule[winnerIdx], ctx = ctx) +
                    awardPlacementSide(event = event, match = match, side = loserSide, placePoints = schedule[loserIdx], ctx = ctx)
            awardCount += rows
            total = total.add(BigDecimal(schedule[winnerIdx] * winnerSide.userIds.size + schedule[loserIdx] * loserSide.userIds.size))
            if (rows > 0) matchCount += 1
        }
        return AwardSummary(matchCount = matchCount, awardCount = awardCount, totalPoints = total)
    }

    /** A COMPLETED placement match with a bracket and a winner — the only fixtures that pay placement points. */
    private fun isAwardablePlacement(match: Match): Boolean =
        match.status == MatchStatus.COMPLETED && match.isPlacementMatch && match.placementBracket != null && match.winnerTeamId != null

    /** (winner index, loser index) into the placement schedule: Super Finals → 1st/2nd, Plate Finals → 3rd/4th. */
    private fun placeIndices(bracket: PlacementBracket): Pair<Int, Int> =
        when (bracket) {
            PlacementBracket.SUPER_FINALS -> PLACE_FIRST to PLACE_SECOND
            PlacementBracket.PLATE_FINALS -> PLACE_THIRD to PLACE_FOURTH
        }

    /** Award [placePoints] to every member of [side], each tagged with their own band + sex. Returns rows written. */
    private fun awardPlacementSide(
        event: Event,
        match: Match,
        side: MatchSide,
        placePoints: Int,
        ctx: AwardContext,
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
                        points = BigDecimal(placePoints),
                        band = band,
                        sex = ctx.sexes.getOrDefault(key = userId, defaultValue = UNSPECIFIED_SEX),
                        pointClass = PointClass.ANNUAL_TOURNAMENT,
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
                        points = placePoints,
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
            AwardContext(
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
    private data class AwardContext(
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
        ctx: AwardContext,
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
}
