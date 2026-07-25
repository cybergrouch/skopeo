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
import org.skopeo.model.OpenPlayPointsConfig
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
import org.skopeo.service.settings.PointsConfigService
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.util.UUID

// Only TOURNAMENT (placement points) and OPEN_PLAY (computed per-set points, #525) award on
// finalize; LEAGUE is intentionally excluded for now (its points model is deferred to a follow-up
// issue), so finalizing a league event awards nothing. See the `when` in [awardForFinalizedEvent].

private const val UNSPECIFIED_SEX = "Unspecified"
private const val BAND_MEAN_SCALE = 4

// Zero-based indices into the placement schedule for each finishing place (1st..4th).
private const val PLACE_FIRST = 0
private const val PLACE_SECOND = 1
private const val PLACE_THIRD = 2
private const val PLACE_FOURTH = 3

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
    private val pointsConfig: PointsConfigService = PointsConfigService(),
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
        when {
            // "Award Ranking Points" unchecked (#559): finalizing awards nothing, whatever the type.
            !event.awardRankingPoints -> AwardSummary(matchCount = 0, awardCount = 0, totalPoints = BigDecimal.ZERO)
            event.type == EventType.OPEN_PLAY -> awardComputedOpenPlay(event = event, grantedBy = grantedBy, now = now)
            event.type == EventType.TOURNAMENT -> awardPlacement(event = event, grantedBy = grantedBy, now = now)
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
        val tournament = pointsConfig.getTournament().value
        val sanctioned = event.clubId?.let { clubs.findById(id = it)?.tournamentsSanctioned } ?: false
        val schedule = tournament.schedule(sanctioned = sanctioned)
        // Validity runs from the event end for the configured tournament window (#559: no per-event override).
        val start = event.endDate
        val end = event.endDate.plusDays(tournament.validityDays.toLong())
        val placementMatches = matches.listByEvent(eventId = event.id).filter { isAwardablePlacement(match = it) }
        val hasCompletedPlate = placementMatches.any { it.placementBracket == PlacementBracket.PLATE_FINALS }
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

        // A player earns exactly one placement award — their best (ctx.awarded is the guard). Processing
        // matches best-place-first (championship → plate → semis) means a semi loser paid 3rd/4th via the
        // Plate Finals is never also paid the flat semi rate.
        var matchCount = 0
        var awardCount = 0
        var total = BigDecimal.ZERO
        // placementMatches are already awardable (non-null bracket, per isAwardablePlacement); carry the
        // non-null bracket so the when's are exhaustive without an unreachable null arm.
        placementMatches
            .mapNotNull { match -> match.placementBracket?.let { bracket -> match to bracket } }
            .sortedBy { (_, bracket) ->
                // Best-place-first, so the ctx.awarded guard keeps a player's best placement.
                when (bracket) {
                    PlacementBracket.CHAMPIONSHIP_FINALS -> PLACE_FIRST
                    PlacementBracket.PLATE_FINALS -> PLACE_SECOND
                    PlacementBracket.SEMI_FINALS_NO_PLATE -> PLACE_THIRD
                    PlacementBracket.SEMI_FINALS_WITH_PLATE -> PLACE_FOURTH
                }
            }.forEach { (match, bracket) ->
                var rows = 0
                placementSides(match = match, bracket = bracket, hasCompletedPlate = hasCompletedPlate).forEach { (side, placeIndex) ->
                    val written =
                        awardPlacementSide(event = event, match = match, side = side, placePoints = schedule[placeIndex], ctx = ctx)
                    rows += written
                    total = total.add(BigDecimal(schedule[placeIndex] * written))
                }
                awardCount += rows
                if (rows > 0) matchCount += 1
            }
        return AwardSummary(matchCount = matchCount, awardCount = awardCount, totalPoints = total)
    }

    /** A COMPLETED placement match with a bracket and a winner — the only fixtures that pay placement points. */
    private fun isAwardablePlacement(match: Match): Boolean =
        match.status == MatchStatus.COMPLETED && match.isPlacementMatch && match.placementBracket != null && match.winnerTeamId != null

    /**
     * The (side, place-index) awards a placement match yields (#552): Championship → winner 1st, loser
     * 2nd; Plate → winner 3rd, loser 4th; Semi (no plate) → loser 3rd (flat); Semi (with plate) → nothing
     * unless there is no completed Plate Finals, in which case the loser gets the flat 3rd (fallback).
     */
    private fun placementSides(
        match: Match,
        bracket: PlacementBracket,
        hasCompletedPlate: Boolean,
    ): List<Pair<MatchSide, Int>> {
        val team1Won = match.winnerTeamId == match.team1.teamId
        val winnerSide = if (team1Won) match.team1 else match.team2
        val loserSide = if (team1Won) match.team2 else match.team1
        return when (bracket) {
            PlacementBracket.CHAMPIONSHIP_FINALS -> listOf(winnerSide to PLACE_FIRST, loserSide to PLACE_SECOND)
            PlacementBracket.PLATE_FINALS -> listOf(winnerSide to PLACE_THIRD, loserSide to PLACE_FOURTH)
            PlacementBracket.SEMI_FINALS_NO_PLATE -> listOf(element = loserSide to PLACE_THIRD)
            PlacementBracket.SEMI_FINALS_WITH_PLATE -> if (hasCompletedPlate) emptyList() else listOf(element = loserSide to PLACE_THIRD)
        }
    }

    /** Award [placePoints] to each not-yet-awarded member of [side], tagged with their band + sex. Returns rows written. */
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
            if (!ctx.awarded.add(element = userId)) return@forEach
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
     * including zero and negative totals. Each row is tagged with the recipient's own band + sex. Points
     * follow the admin-configurable margin-bracket schedule (#553); the validity window is the event's if
     * set, else defaults to the configured open-play validity from [event end].
     */
    private fun awardComputedOpenPlay(
        event: Event,
        grantedBy: UUID,
        now: LocalDateTime,
    ): AwardSummary {
        val config: OpenPlayPointsConfig = pointsConfig.getOpenPlay().value
        // Validity runs from the event end for the configured open-play window (#559: no per-event override).
        val validFrom = event.endDate.atStartOfDay()
        val validUntil = event.endDate.plusDays(config.validityDays.toLong()).plusDays(1).atStartOfDay()
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
                    config = config,
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
        // Placement only (#552): players already paid, so each earns exactly one placement award (best).
        val awarded: MutableSet<UUID> = mutableSetOf(),
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
