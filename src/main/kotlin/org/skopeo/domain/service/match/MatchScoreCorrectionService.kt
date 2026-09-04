// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.match

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.jetbrains.exposed.sql.transactions.transaction
import org.skopeo.common.dto.match.MatchCorrectionPlayerImpact
import org.skopeo.common.dto.match.MatchResultRequest
import org.skopeo.common.dto.match.MatchScoreCorrectionRequest
import org.skopeo.common.dto.match.MatchScoreCorrectionResponse
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.entity.event.toDomain
import org.skopeo.domain.mapper.entity.match.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuditEntityType
import org.skopeo.domain.model.AuditWrite
import org.skopeo.domain.model.Level
import org.skopeo.domain.model.Match
import org.skopeo.domain.model.MatchSetResult
import org.skopeo.domain.model.PlayerChange
import org.skopeo.domain.model.RatingHistoryEntry
import org.skopeo.domain.model.RatingHistoryWrite
import org.skopeo.domain.model.User
import org.skopeo.domain.service.GroupClassifier
import org.skopeo.domain.service.audit.AuditService
import org.skopeo.domain.service.calculator.RankingCalculator
import org.skopeo.domain.service.calculator.impl.v2.PerformanceBasedRankingCalculatorImpl
import org.skopeo.domain.service.event.EventFinalizeAwarder
import org.skopeo.domain.service.rating.RatingAssembler
import org.skopeo.domain.service.rating.breakdownsFromAudit
import org.skopeo.domain.service.rating.buildRequest
import org.skopeo.domain.service.rating.groupsFor
import org.skopeo.domain.service.rating.playerChangeFrom
import org.skopeo.domain.service.rating.toSnapshot
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.domain.service.user.displayName
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.UserRepository
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

// The NTRP band a rating is clamped into. Mirrors the calculator's own bounds; a correction re-clamps only
// the FINAL result, never the reversal step (see the class doc).
private val NTRP_MIN = BigDecimal("1.0")
private val NTRP_MAX = BigDecimal("7.0")

/**
 * Correct the score of an ALREADY-RATED match (#776), ADMINISTRATOR only.
 *
 * The rated-path complement of [MatchService.uploadResult], which freezes a match once `rated_at` is set.
 * Where event-scoped Reverse Ratings (#478) restores each participant's PRE-EVENT snapshot — sound only at
 * the rated tip — this handles the mid-history case: a match with several later rated matches behind it.
 * For each player it swaps one delta for another against their CURRENT rating:
 *
 * ```
 * newCurrent(p) = clamp(currentRating(p) - oldDelta(p) + newDelta(p))
 * ```
 *
 * - `oldDelta` is the `rating_change` on the match's live history row, reversed **verbatim** — that is the
 *   value actually applied, post-clamp and post-smoothing.
 * - `newDelta` is recomputed for the corrected score from the player's rating **at the time** (the row's
 *   `previous_rating`), NOT from their present-day rating. That keeps the recomputation faithful to what
 *   should have been calculated then, and makes the arithmetic a clean swap.
 *
 * Nothing downstream is re-rated: matches rated after this one keep their ratings and history. The players'
 * later deltas were computed against the wrong intermediate rating and would have differed slightly — an
 * accepted approximation, not a defect. See docs/product/MATCH_SCORE_CORRECTION.md for the full rationale,
 * the confidence/clamping analysis, and the invariants this must preserve.
 *
 * Dry-run is the DEFAULT: a full preview with no writes, which is the confirmation surface the admin
 * approves. Only an explicit `dryRun = false` writes, and then everything lands in one transaction.
 */
class MatchScoreCorrectionService(
    private val matches: MatchRepository = MatchRepository(),
    private val ratings: RatingAssembler = RatingAssembler(),
    private val users: UserRepository = UserRepository(),
    private val events: EventRepository = EventRepository(),
    private val awards: RankingPointRepository = RankingPointRepository(),
    private val awarder: EventFinalizeAwarder = EventFinalizeAwarder(),
    private val calculator: RankingCalculator = PerformanceBasedRankingCalculatorImpl(),
    private val audit: AuditService = AuditService(),
    private val classifier: GroupClassifier = GroupClassifier(),
) {
    /** One player's before/after, assembled once and used for both the preview and the commit. */
    private data class Impact(
        val userId: UUID,
        val currentRating: BigDecimal,
        val oldDelta: BigDecimal,
        val newChange: PlayerChange,
        val resultingRating: BigDecimal,
        val resultingLevel: String?,
        // The history row being superseded — its `previous_rating`/`previous_level` are the historical
        // context the replacement row records.
        //
        // **Null when no rating was applied to this player for this match** (#881): calibration suppressed
        // it, so there is no row, nothing to reverse, and nothing to re-apply. Not an error — a valid
        // state that this service must carry rather than reject.
        val reversed: RatingHistoryEntry?,
    ) {
        /** True when nothing was applied to this player, so the correction must leave them untouched. */
        val wasSuppressed: Boolean get() = reversed == null

        val netAdjustment: BigDecimal get() = if (wasSuppressed) BigDecimal.ZERO else newChange.change - oldDelta
        val levelChanged: Boolean get() = !wasSuppressed && reversed?.previousLevel != resultingLevel
    }

    fun correctScore(
        token: VerifiedFirebaseToken,
        matchId: UUID,
        request: MatchScoreCorrectionRequest,
    ): Either<ServiceError, MatchScoreCorrectionResponse> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val match = matches.findById(matchId = matchId).bind().toDomain()
            ensure(condition = match.isActive) { ServiceError.Conflict(message = "Match is disabled") }
            // The unrated path is MatchService.uploadResult; point the caller there rather than duplicating it.
            ensure(condition = match.ratedAt != null) {
                ServiceError.Conflict(message = "Match is not rated yet — edit the result normally instead")
            }

            // The live history rows for this match ARE the historical inputs: previous_rating (what the delta
            // was computed from) and rating_change (what was applied). Without them there is nothing to reverse.
            val history = ratings.historyForMatches(matchIds = listOf(element = matchId)).associateBy { it.userId }
            val players = match.team1.userIds + match.team2.userIds
            // Requires a row for at least ONE player, not for every player (#881).
            //
            // A calibration-suppressed player HAS no row — nothing was applied to them — and demanding one
            // made any match involving a calibrating player permanently uncorrectable, reporting that its
            // ratings "cannot be reversed" when in truth there was nothing to reverse for that player.
            //
            // The floor still matters: a rated match where NO player has a row is genuinely inconsistent,
            // since suppression only ever applies to players who were not calibrating while someone else
            // was — it can never suppress everyone.
            ensure(condition = players.any { it in history }) {
                ServiceError.Conflict(
                    message = "Match $matchId has no live rating history for any player; its ratings cannot be reversed",
                )
            }

            val (correctedSets, newWinner) =
                deriveOutcome(
                    team1Id = match.team1.teamId,
                    team2Id = match.team2.teamId,
                    request = MatchResultRequest(sets = request.sets),
                ).bind()

            val impacts = impactsFor(match = match, correctedSets = correctedSets, newWinner = newWinner, history = history).bind()
            val usersById = users.findAllByIds(ids = players).map { it.toDomain() }.associateBy { it.id }
            val previousScore = scoreOf(sets = match.sets)
            val newScore = scoreOf(sets = correctedSets)

            val points =
                if (request.dryRun) {
                    PointsOutcome()
                } else {
                    apply(
                        match = match,
                        correctedSets = correctedSets,
                        newWinner = newWinner,
                        impacts = impacts,
                        adminId = adminId,
                        previousScore = previousScore,
                        newScore = newScore,
                    )
                }
            response(
                request = request,
                match = match,
                newWinner = newWinner,
                previousScore = previousScore,
                newScore = newScore,
                impacts = impacts,
                usersById = usersById,
                points = points,
            )
        }

    /** Tallies from the points half of a commit; all-zero for a dry run or a match that pays no points. */
    private data class PointsOutcome(
        val revoked: Int = 0,
        val reissued: Int = 0,
        val suppressedByGlobalFlag: Boolean = false,
    )

    /**
     * The rating [userId] held when [match] was rated.
     *
     * For a player whose delta was applied, that is recorded on their own history row and is used
     * verbatim. A **calibration-suppressed** player has no row for this match (#881), so it is
     * reconstructed from their most recent history row calculated at or before the match's rating time —
     * i.e. wherever their rating stood when this match was computed.
     *
     * Falls back to their current rating when they have no earlier history at all, which is the case for
     * a player whose only rating is the manual designation itself. That is exact in the common case: with
     * no matches rated since, current *is* the rating they held.
     *
     * This is an input to the recomputation rather than something written back, but it is not cosmetic —
     * the rating gap it feeds decides the other side's corrected delta.
     */
    private fun historicalRatingFor(
        userId: UUID,
        match: Match,
        row: RatingHistoryEntry?,
    ): BigDecimal {
        if (row != null) {
            return row.previousRating
        }
        val ratedAt = match.ratedAt
        val priorRating =
            ratings
                .historyByUser(userId = userId)
                .filter { entry -> ratedAt == null || !entry.calculatedAt.isAfter(ratedAt) }
                .maxByOrNull { it.calculatedAt }
                ?.newRating
        return priorRating ?: ratings.findCurrentRating(userId = userId)?.currentRating ?: BigDecimal.ZERO
    }

    /**
     * Recompute each player's delta for the corrected score and pair it with the delta being reversed.
     *
     * The calculator is fed the players' ratings AT THE TIME — `previous_rating` from the row being
     * superseded — so the new delta is what should have been calculated then. Only afterwards is the
     * difference applied to the present-day rating.
     */
    private fun impactsFor(
        match: Match,
        correctedSets: List<MatchSetResult>,
        newWinner: UUID,
        history: Map<UUID, RatingHistoryEntry>,
    ): Either<ServiceError, List<Impact>> =
        either {
            val players = match.team1.userIds + match.team2.userIds
            // The rating each player held when this match was rated. For a rated player that is on their
            // row; for a calibration-suppressed player there is no row, so it is reconstructed from their
            // most recent history row before this match, falling back to their current rating.
            //
            // It matters even though their own rating will not move: it is an INPUT to the recomputation,
            // so the rating gap — and therefore the other side's corrected delta — depends on it.
            val historicalRatings = players.associateWith { historicalRatingFor(userId = it, match = match, row = history[it]) }
            val groups = groupsFor(users = users, classifier = classifier, userIds = players, format = match.matchFormat)

            // The corrected match as the calculator should see it: the new sets and winner, everything else
            // (handicaps, match type, date) exactly as recorded.
            val corrected = match.copy(sets = correctedSets, winnerTeamId = newWinner)
            val result =
                calculator.calculate(
                    request = buildRequest(match = corrected, ratingsByUser = historicalRatings, groupsByUser = groups),
                )
            val breakdowns = breakdownsFromAudit(audit = result.audit)

            players.map { userId ->
                val change = playerChangeFrom(userId = userId, response = result.response, breakdowns = breakdowns).bind()
                val reversed = history[userId]
                val current =
                    ensureNotNull(value = ratings.findCurrentRating(userId = userId)?.currentRating) {
                        ServiceError.Conflict(message = "User $userId has no current rating to correct")
                    }
                // A player who was suppressed STAYS suppressed (#881). The suppression belongs to the
                // state as it was when the match was rated — which the presence or absence of a row
                // records — not to the state now. Otherwise correcting a match after its calibration
                // window closed would retroactively start moving a settled opponent's rating, applying a
                // change that was deliberately withheld at the time.
                val resulting =
                    if (reversed == null) current else clamp(value = current - reversed.ratingChange + change.change)
                Impact(
                    userId = userId,
                    currentRating = current,
                    oldDelta = reversed?.ratingChange ?: BigDecimal.ZERO,
                    newChange = change,
                    resultingRating = resulting,
                    resultingLevel = Level.fromValue(value = resulting.toPlainString()).value,
                    reversed = reversed,
                )
            }
        }

    /**
     * Persist the correction. In ONE transaction, mirroring how the calculation wrote these values so this
     * unwinds them consistently:
     *
     *  1. **Replace** the score (sets, per-set winners, tiebreaks, `winner_team_id`).
     *  2. **Supersede** the match's live rating-history rows (`reversed_at`) — soft-delete, never a delete.
     *  3. **Append** a replacement row per player, stamped `corrected_at` with the `net_adjustment` applied.
     *  4. **Apply** each player's resulting rating/level — and ONLY those: `matches_played`,
     *     `last_match_date`, `match_rated_at` and `matches_since_reset` must not move, since the match was
     *     already counted. Confidence (#459) is computed on read from match dates, so it is untouched.
     *  5. **Stamp** the re-rated marker; `rated_at` deliberately stays set so the match never re-enters the
     *     pending-calculation queue (which would double-apply the delta).
     *
     * The points half runs after, outside that transaction, because awarding records its own audit entries.
     */
    @Suppress("LongParameterList") // One cohesive commit; splitting it would just thread the same values.
    private fun apply(
        match: Match,
        correctedSets: List<MatchSetResult>,
        newWinner: UUID,
        impacts: List<Impact>,
        adminId: UUID,
        previousScore: String,
        newScore: String,
    ): PointsOutcome {
        val now = LocalDateTime.now()
        transaction {
            matches.addResult(
                matchId = match.id,
                sets = correctedSets,
                winnerTeamId = newWinner,
                recordedBy = adminId,
                // Preserve the original completion time: a correction fixes the score, not when it was played.
                completedAt = match.completedAt ?: now,
            )
            ratings.markMatchHistoryReversed(matchId = match.id, reversedAt = now)
            // A suppressed player had no row to supersede and gets no replacement (#881): the correction
            // reverses exactly what was applied, per player, or nothing. Writing a row here would invent a
            // change that never happened and hand the next correction something false to reverse.
            impacts.mapNotNull { impact -> impact.reversed?.let { impact to it } }.forEach { (impact, reversed) ->
                ratings.appendHistory(
                    write =
                        RatingHistoryWrite(
                            userId = impact.userId,
                            matchId = match.id,
                            // Historical context, so the row reads as the calculation that SHOULD have happened.
                            previousRating = reversed.previousRating,
                            newRating = impact.newChange.newRating,
                            ratingChange = impact.newChange.change,
                            percentChange = impact.newChange.percentChange,
                            previousLevel = reversed.previousLevel,
                            newLevel = impact.newChange.newLevel,
                            levelChanged = impact.newChange.levelChanged,
                            breakdown = impact.newChange.breakdown.toSnapshot(),
                            completedAt = match.completedAt,
                            calculatedAt = now,
                            // Not a calc batch; the correction marker below is this row's identity instead.
                            ratingRunId = null,
                            correctedAt = now,
                            netAdjustment = impact.netAdjustment,
                        ),
                )
                ratings.applyCorrectedRating(
                    userId = impact.userId,
                    rating = impact.resultingRating,
                    level = impact.resultingLevel,
                )
            }
            matches.markReRated(matchId = match.id, reRatedAt = now)
        }
        val points = correctPoints(match = match, adminId = adminId, now = now)
        recordAudit(
            match = match,
            impacts = impacts,
            adminId = adminId,
            previousScore = previousScore,
            newScore = newScore,
            winnerChanged = match.winnerTeamId != newWinner,
            points = points,
        )
        return points
    }

    /**
     * Reverse and re-apply the corrected match's ranking points (#776). Revocation is append-only (a REVOKED
     * marker per award), matching the #478 reversal.
     *
     * Scope differs by event type, and the awarder owns that distinction: OPEN_PLAY pays per match with no
     * cross-match coupling, so only this match's awards are touched; TOURNAMENT pays by placement under a
     * best-placement-per-player guard that spans matches, so a corrected placement match needs the event's
     * whole placement set recomputed. An eventless (open) match pays no points at all.
     */
    private fun correctPoints(
        match: Match,
        adminId: UUID,
        now: LocalDateTime,
    ): PointsOutcome {
        val event = match.eventId?.let { events.findById(id = it)?.toDomain() } ?: return PointsOutcome()
        val toRevoke =
            if (match.isPlacementMatch) {
                awards.listActiveByEvent(eventId = event.id)
            } else {
                awards.listActiveByMatch(matchId = match.id)
            }
        toRevoke.forEach { award ->
            awards.revoke(
                awardId = award.id,
                revokedBy = adminId,
                reason = "Reversed on score correction of match ${match.publicCode}",
                revokedAt = now,
            )
        }
        val summary = awarder.awardForFinalizedEvent(event = event, grantedBy = adminId, now = now, correctedMatchId = match.id)
        return PointsOutcome(
            revoked = toRevoke.size,
            reissued = summary.awardCount,
            suppressedByGlobalFlag = summary.suppressedByGlobalFlag,
        )
    }

    /**
     * Two Activity Log entries per correction, deliberately (#776): the score edit under MATCH_RESULT and the
     * re-rating under RATING_CALCULATION, so each half can be filtered on its own. They share the match id as
     * the correlating key.
     */
    @Suppress("LongParameterList") // Audit detail is inherently wide; each value is reported verbatim.
    private fun recordAudit(
        match: Match,
        impacts: List<Impact>,
        adminId: UUID,
        previousScore: String,
        newScore: String,
        winnerChanged: Boolean,
        points: PointsOutcome,
    ) {
        audit.record(
            write =
                AuditWrite(
                    actorUserId = adminId,
                    action = AuditAction.MATCH_SCORE_CORRECTED,
                    entityType = AuditEntityType.MATCH,
                    entityId = match.id,
                    summary = "Corrected the score of rated match ${match.publicCode}: $previousScore → $newScore",
                    details =
                        mapOf(
                            "matchPublicCode" to match.publicCode,
                            "previousScore" to previousScore,
                            "newScore" to newScore,
                            "winnerChanged" to winnerChanged.toString(),
                        ),
                ),
        )
        audit.record(
            write =
                AuditWrite(
                    actorUserId = adminId,
                    action = AuditAction.MATCH_RATINGS_RE_RATED,
                    entityType = AuditEntityType.MATCH,
                    entityId = match.id,
                    summary =
                        "Re-rated match ${match.publicCode} after a score correction: reversed and re-applied " +
                            "${impacts.size} player rating change(s)",
                    details =
                        mapOf(
                            "matchPublicCode" to match.publicCode,
                            "players" to impacts.size.toString(),
                            "adjustments" to
                                impacts.joinToString(separator = "; ") { impact ->
                                    "${impact.userId}: ${impact.currentRating.toPlainString()} " +
                                        "-${impact.oldDelta.toPlainString()} " +
                                        "+${impact.newChange.change.toPlainString()} " +
                                        "= ${impact.resultingRating.toPlainString()}"
                                },
                            "awardsRevoked" to points.revoked.toString(),
                            "awardsReissued" to points.reissued.toString(),
                        ),
                ),
        )
    }

    @Suppress("LongParameterList") // Assembles the wire response from the already-computed parts.
    private fun response(
        request: MatchScoreCorrectionRequest,
        match: Match,
        newWinner: UUID,
        previousScore: String,
        newScore: String,
        impacts: List<Impact>,
        usersById: Map<UUID, User>,
        points: PointsOutcome,
    ): MatchScoreCorrectionResponse =
        MatchScoreCorrectionResponse(
            dryRun = request.dryRun,
            matchPublicCode = match.publicCode,
            previousScore = previousScore,
            newScore = newScore,
            winnerChanged = match.winnerTeamId != newWinner,
            impacts =
                impacts.map { impact ->
                    MatchCorrectionPlayerImpact(
                        userId = impact.userId.toString(),
                        displayName = usersById[impact.userId]?.displayName(),
                        currentRating = impact.currentRating.toPlainString(),
                        reversedChange = impact.oldDelta.toPlainString(),
                        // Zero for a suppressed player: the recomputed delta is real but was never going
                        // to be applied, and reporting it would read as a change that is about to happen.
                        newChange =
                            if (impact.wasSuppressed) {
                                BigDecimal.ZERO.toPlainString()
                            } else {
                                impact.newChange.change.toPlainString()
                            },
                        netAdjustment = impact.netAdjustment.toPlainString(),
                        resultingRating = impact.resultingRating.toPlainString(),
                        previousLevel = impact.reversed?.previousLevel,
                        resultingLevel = impact.resultingLevel,
                        levelChanged = impact.levelChanged,
                        wasSuppressed = impact.wasSuppressed,
                    )
                },
            awardsRevoked = points.revoked,
            awardsReissued = points.reissued,
            pointsSuppressedByGlobalFlag = points.suppressedByGlobalFlag,
        )

    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
        return if (caller == null || !caller.capabilities.contains(element = Capability.ADMINISTRATOR)) {
            ServiceError.Forbidden().left()
        } else {
            caller.id.right()
        }
    }
}

/** Clamp a corrected rating into the NTRP band, exactly as the calculator clamps its own results. */
private fun clamp(value: BigDecimal): BigDecimal = value.max(NTRP_MIN).min(NTRP_MAX)

/** A match score as a readable "6-4, 3-6, 7-5" string, for the audit trail and the preview. */
private fun scoreOf(sets: List<MatchSetResult>): String =
    sets.sortedBy { it.setNumber }.joinToString(separator = ", ") { "${it.team1Games}-${it.team2Games}" }
