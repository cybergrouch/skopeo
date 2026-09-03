// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.ranking

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.skopeo.common.dto.ranking.AdjustRankingPointsRequest
import org.skopeo.common.dto.ranking.AwardedPointsPageResponse
import org.skopeo.common.dto.ranking.AwardedPointsPlayerRow
import org.skopeo.common.dto.ranking.AwardedPointsSummaryResponse
import org.skopeo.common.dto.ranking.GrantRankingPointsRequest
import org.skopeo.common.dto.ranking.RankingPointAwardResponse
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.dto.ranking.toCommand
import org.skopeo.domain.mapper.dto.ranking.toResponse
import org.skopeo.domain.mapper.entity.ranking.toDomain
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuditEntityType
import org.skopeo.domain.model.AuditWrite
import org.skopeo.domain.model.AwardStatus
import org.skopeo.domain.model.AwardsPage
import org.skopeo.domain.model.Level
import org.skopeo.domain.model.PointClass
import org.skopeo.domain.model.PointSourceType
import org.skopeo.domain.model.RankingPointAward
import org.skopeo.domain.model.RankingPointAwardWrite
import org.skopeo.domain.model.ResolvedAward
import org.skopeo.domain.service.audit.AuditService
import org.skopeo.domain.service.rating.RatingAssembler
import org.skopeo.domain.service.user.VerifiedFirebaseToken
import org.skopeo.domain.service.user.displayName
import org.skopeo.domain.service.user.isDeleted
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.UserRepository
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import org.skopeo.domain.mapper.entity.event.toDomain as toEventDomain

private const val DEFAULT_PAGE_SIZE = 25
private const val MAX_PAGE_SIZE = 100

/**
 * Admin-only management of the ranking-points ledger (#146, phase 1): grant, revoke, and list a
 * user's awards. Every award is **band-tagged at award time** (the design's decision B) — the band
 * defaults to the target's current NTRP band from their rating, overridable per grant; [sex] always
 * comes from the target user. Validity defaults from the [PointClass][org.skopeo.domain.model.PointClass]
 * policy and is overridable. External / ad-hoc grants require a [reason] for traceability (§7).
 *
 * No snapshot/recompute here (that is #146 phase 2, which reuses #220); tournament auto-awards are
 * gated on the tournaments model (#390). Expected failures are an [Either] left ([ServiceError], #115).
 */
class RankingPointService(
    private val awards: RankingPointRepository = RankingPointRepository(),
    private val users: UserRepository = UserRepository(),
    private val ratings: RatingAssembler = RatingAssembler(),
    private val matches: MatchRepository = MatchRepository(),
    private val events: EventRepository = EventRepository(),
    private val audit: AuditService = AuditService(),
) {
    /** Grant an award to a user. ADMINISTRATOR-only; band-tagged, sex from the target, policy validity. */
    fun grant(
        token: VerifiedFirebaseToken,
        userId: UUID,
        request: GrantRankingPointsRequest,
    ): Either<ServiceError, RankingPointAwardResponse> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val command = request.toCommand(userId = userId)
            ensure(condition = command.points > BigDecimal.ZERO) {
                ServiceError.Validation(message = "Points must be greater than zero")
            }
            // Decision #6 (#403): points are stored as BigDecimal but must always be whole integers —
            // stripTrailingZeros so 100.0000 is accepted while a fractional grant like 100.5 is rejected.
            ensure(condition = command.points.stripTrailingZeros().scale() <= 0) {
                ServiceError.Validation(message = "Points must be a whole number")
            }
            // An external / ad-hoc grant must justify itself (§7 traceability).
            ensure(condition = command.sourceType == PointSourceType.INTERNAL || !command.reason.isNullOrBlank()) {
                ServiceError.Validation(message = "A reason is required for an external or ad-hoc grant")
            }
            val target =
                users.findById(
                    id = command.userId,
                ).mapLeft { ServiceError.Validation(message = "Unknown user ${command.userId}") }.bind().toDomain()
            ensure(condition = target.isActive) { ServiceError.Validation(message = "User ${command.userId} is not active") }

            val validFrom = command.validFrom ?: LocalDateTime.now()
            val validUntil = command.validUntil ?: command.pointClass.defaultValidUntil(from = validFrom)
            ensure(condition = validUntil.isAfter(validFrom)) {
                ServiceError.Validation(message = "valid_until must be after valid_from")
            }
            // Band recorded at award time: an explicit override, else the target's current band. An
            // unrated player with no explicit band has nothing to tag → Validation.
            val resolvedBand = command.band?.ifBlank { null } ?: currentBand(userId = command.userId)
            val band =
                ensureNotNull(value = resolvedBand) {
                    ServiceError.Validation(message = "User ${command.userId} has no rating; supply an explicit band")
                }

            val award =
                awards.award(
                    write =
                        RankingPointAwardWrite(
                            userId = command.userId,
                            points = command.points,
                            pointClass = command.pointClass,
                            sourceType = command.sourceType,
                            sourceId = command.sourceId?.ifBlank { null },
                            band = band,
                            sex = target.sex ?: "Unspecified",
                            reason = command.reason?.ifBlank { null },
                            validFrom = validFrom,
                            validUntil = validUntil,
                            status = AwardStatus.ACTIVE,
                            revokesAwardId = null,
                            grantedBy = adminId,
                            awardedAt = LocalDateTime.now(),
                        ),
                ).toDomain()
            audit.record(write = grantAudit(actorId = adminId, award = award))
            award.toResponse()
        }

    /**
     * A manual, signed point adjustment (#469): an admin awards (+) or deducts (−) points for a player
     * from the Manage Player section. It writes a non-budgeted EXTERNAL ledger entry via [award], reusing
     * the same audit provenance as [grant] (target = the player, #471). Differs from [grant] in three
     * ways: [points] are **signed** (positive or negative, non-zero, whole), the [reason] is **always
     * required**, and the validity window is **explicit** (no point-class default). Band = the player's
     * current band (an unrated player has nothing to tag → Validation); sex from the player. It is queued:
     * the next [StandingsCalculationService][org.skopeo.domain.service.standings.StandingsCalculationService]
     * recompute sums it (a normal ACTIVE, in-window award), while the live points audit (#448) shows it now.
     */
    fun adjust(
        token: VerifiedFirebaseToken,
        userId: UUID,
        request: AdjustRankingPointsRequest,
    ): Either<ServiceError, RankingPointAwardResponse> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val command = request.toCommand(userId = userId)
            // Signed: a positive value awards, a negative value deducts — but zero is a no-op → reject it.
            ensure(condition = command.points.signum() != 0) {
                ServiceError.Validation(message = "Points must not be zero")
            }
            // Decision #6 (#403): points are whole integers — stripTrailingZeros so 100.0000 / -50.0000 pass
            // while a fractional adjustment (e.g. 100.5) is rejected. signum-independent, so it also covers −.
            ensure(condition = command.points.stripTrailingZeros().scale() <= 0) {
                ServiceError.Validation(message = "Points must be a whole number")
            }
            // A manual adjustment must always justify itself (the comment / rationale is mandatory, #469).
            ensure(condition = command.reason.isNotBlank()) {
                ServiceError.Validation(message = "A reason is required for a manual adjustment")
            }
            ensure(condition = command.validUntil.isAfter(command.validFrom)) {
                ServiceError.Validation(message = "valid_until must be after valid_from")
            }
            val target =
                users.findById(
                    id = command.userId,
                ).mapLeft { ServiceError.Validation(message = "Unknown user ${command.userId}") }.bind().toDomain()
            ensure(condition = target.isActive) { ServiceError.Validation(message = "User ${command.userId} is not active") }

            // Band tagged from the player's current rating; an unrated player has nothing to tag → Validation.
            val band =
                ensureNotNull(value = currentBand(userId = command.userId)) {
                    ServiceError.Validation(message = "User ${command.userId} has no rating; cannot band-tag the adjustment")
                }

            val award =
                awards.award(
                    write =
                        RankingPointAwardWrite(
                            userId = command.userId,
                            points = command.points,
                            // Ad-hoc, non-budgeted (decision #5): an EXTERNAL class + EXTERNAL source, no club/budget.
                            pointClass = PointClass.EXTERNAL,
                            sourceType = PointSourceType.EXTERNAL,
                            sourceId = null,
                            band = band,
                            sex = target.sex ?: "Unspecified",
                            reason = command.reason.trim(),
                            validFrom = command.validFrom,
                            validUntil = command.validUntil,
                            status = AwardStatus.ACTIVE,
                            revokesAwardId = null,
                            grantedBy = adminId,
                            awardedAt = LocalDateTime.now(),
                        ),
                ).toDomain()
            audit.record(write = grantAudit(actorId = adminId, award = award))
            award.toResponse()
        }

    /**
     * The provenance record for a grant (#146, §7): actor + amount + class + band + source + reason.
     * Target = the awarded player (USER, #471) so the Activity Log's Target column links to the player,
     * consistent with the finalize-time per-award audit; the award row id is kept in details.
     */
    private fun grantAudit(
        actorId: UUID,
        award: RankingPointAward,
    ): AuditWrite =
        AuditWrite(
            actorUserId = actorId,
            action = AuditAction.RANKING_POINTS_AWARDED,
            entityType = AuditEntityType.USER,
            entityId = award.userId,
            summary = "Awarded ${award.points.toPlainString()} ${award.pointClass.name} points (band ${award.band})",
            details =
                mapOf(
                    "awardId" to award.id.toString(),
                    "userId" to award.userId.toString(),
                    "points" to award.points.toPlainString(),
                    "pointClass" to award.pointClass.name,
                    "band" to award.band,
                    "sourceType" to award.sourceType.name,
                    "sourceId" to award.sourceId,
                    "reason" to award.reason,
                ),
        )

    /** Revoke an award (append a REVOKED marker + flip the original). ADMINISTRATOR-only, audited. */
    fun revoke(
        token: VerifiedFirebaseToken,
        awardId: UUID,
        reason: String?,
    ): Either<ServiceError, Unit> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val marker =
                ensureNotNull(
                    value =
                        awards.revoke(
                            awardId = awardId,
                            revokedBy = adminId,
                            reason = reason?.ifBlank { null },
                            revokedAt = LocalDateTime.now(),
                        ),
                ) { ServiceError.NotFound(message = "No active ranking-point award $awardId") }
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.RANKING_POINTS_REVOKED,
                        entityType = AuditEntityType.RANKING_POINT,
                        entityId = awardId,
                        summary = "Revoked ranking-point award $awardId",
                        details =
                            mapOf(
                                "userId" to marker.userId.toString(),
                                "revokedAwardId" to awardId.toString(),
                                "markerId" to marker.id.toString(),
                                "reason" to marker.reason,
                            ),
                    ),
            )
        }

    /** List a user's ledger rows, newest first. ADMINISTRATOR-only for now (kept strict, §7). */
    fun listForUser(
        token: VerifiedFirebaseToken,
        userId: UUID,
    ): Either<ServiceError, List<RankingPointAwardResponse>> =
        either {
            requireAdmin(token = token).bind()
            users.findById(id = userId).mapLeft { ServiceError.NotFound(message = "User $userId not found") }.bind().toDomain()
            awards.listByUser(userId = userId).map { it.toDomain().toResponse() }
        }

    /**
     * What an event awarded, per player (#857) — the public event page's points card.
     *
     * **Public: no token, no gate.** The pages this feeds are viewable anonymously (#193), and amounts are
     * already public information — a player's points total is public under the POINTS standings source, and
     * rank/band are public (#64/#114). Only the *derivation* of an amount is gated (#858), and none is
     * carried here.
     *
     * Three deliberate filter choices:
     * - **ACTIVE only.** A revoked award paid nothing, and every standings query filters the same way. A
     *   revocation marker carries zero points, so including it would add a phantom row of "+0".
     * - **Validity is NOT applied**, unlike everywhere else that reads this table. "What did this event
     *   award" does not stop being true when the points later expire; `countsAsOf` is the right rule for
     *   standings and the wrong one for a record of what was paid.
     * - **An event with no awards returns an empty list**, which the client renders as *no card at all*
     *   rather than an empty one — an event may legitimately have none because it is unfinalized or
     *   because its `awardRankingPoints` flag is off (#831).
     */
    fun awardedForEvent(code: String): Either<ServiceError, AwardedPointsSummaryResponse> =
        either {
            // toEventDomain: three entity mappers expose `toDomain`, so the event one is imported aliased.
            val event =
                ensureNotNull(value = events.findByPublicCode(code = code)?.toEventDomain()) {
                    ServiceError.NotFound(message = "Event $code not found")
                }
            summarise(
                awards = awards.listActiveByEvent(eventId = event.id).map { it.toDomain() },
                players = users,
            )
        }

    /**
     * One page of the whole ledger (#472), newest-first, for the Points Management "Points awarded"
     * list. Gated by [requirePointsManager] (POINTS_MANAGER or ADMINISTRATOR), matching the tab. Each
     * row is enriched with the player's display name + public code and the granting source's public
     * code (match, else event; null for a manual / external grant) — all lookups batched to avoid N+1.
     */

    fun listAwards(
        token: VerifiedFirebaseToken,
        limit: Int?,
        offset: Int?,
    ): Either<ServiceError, AwardedPointsPageResponse> =
        either {
            requirePointsManager(token = token).bind()
            val pageSize = (limit ?: DEFAULT_PAGE_SIZE).coerceIn(minimumValue = 1, maximumValue = MAX_PAGE_SIZE)
            val pageOffset = (offset ?: 0).coerceAtLeast(minimumValue = 0)
            val (rowEntities, total) = awards.listAwards(limit = pageSize, offset = pageOffset)
            val rows = rowEntities.map { it.toDomain() }

            val usersById = users.findAllByIds(ids = rows.map { it.userId }).map { it.toDomain() }.associateBy { it.id }
            val matchRefs = matches.publicRefsByIds(ids = rows.mapNotNull { it.matchId })
            val eventCodes = events.publicCodesByIds(ids = rows.mapNotNull { it.eventId })
            val resolved =
                rows.map { award ->
                    val user = usersById[award.userId]
                    ResolvedAward(
                        award = award,
                        playerDisplayName = user?.displayName(),
                        playerPublicCode = user?.publicCode,
                        matchPublicCode = award.matchId?.let { matchRefs[it]?.publicCode },
                        eventPublicCode = award.eventId?.let { eventCodes[it] },
                        playerIsPlaceholder = user?.placeholder ?: false,
                        playerIsDeleted = user?.isDeleted() ?: false,
                    )
                }
            AwardsPage(rows = resolved, total = total.toInt(), limit = pageSize, offset = pageOffset).toResponse()
        }

    /** The target's current NTRP band label (e.g. "4.0"), or null when they have no rating yet. */
    private fun currentBand(userId: UUID): String? {
        val rating = ratings.findCurrentRating(userId = userId) ?: return null
        return Level.fromValue(value = rating.currentRating.toPlainString()).value
    }

    /** ADMINISTRATOR-only access; returns the caller's id (the audit actor). */
    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
        return if (caller == null || !caller.capabilities.contains(element = Capability.ADMINISTRATOR)) {
            ServiceError.Forbidden().left()
        } else {
            caller.id.right()
        }
    }

    /**
     * Points-manager access (#472): ADMINISTRATOR is implicitly a points manager, so the caller passes
     * as an ADMINISTRATOR or a POINTS_MANAGER — matching the Points Management tab. Returns the caller's id.
     */
    private fun requirePointsManager(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
        val allowed =
            caller != null &&
                caller.capabilities.any { it == Capability.ADMINISTRATOR || it == Capability.POINTS_MANAGER }
        return if (caller == null || !allowed) ServiceError.Forbidden().left() else caller.id.right()
    }
}

/**
 * Fold award rows into one row per player, highest total first, with identities resolved in one lookup
 * (#857).
 *
 * File-scope rather than a member: it is a pure fold plus one repository read, so nothing about it belongs
 * to the service's state — and keeping it out of the class holds that class under detekt's function ceiling.
 */
private fun summarise(
    awards: List<RankingPointAward>,
    players: UserRepository,
): AwardedPointsSummaryResponse {
    val byUser =
        awards
            .groupBy { it.userId }
            .mapValues { (_, rows) -> rows.fold(initial = BigDecimal.ZERO) { sum, row -> sum.add(row.points) } }
    val usersById = players.findAllByIds(ids = byUser.keys.toList()).map { it.toDomain() }.associateBy { it.id }
    val rows =
        byUser
            .map { (userId, total) -> userId to total }
            // Highest first, then by id so the order is stable when two players tie — an unstable order
            // would make the card reshuffle between renders.
            .sortedWith(comparator = compareByDescending<Pair<UUID, BigDecimal>> { it.second }.thenBy { it.first.toString() })
            .map { (userId, total) ->
                val user = usersById[userId]
                AwardedPointsPlayerRow(
                    userId = userId.toString(),
                    publicCode = user?.publicCode,
                    displayName = user?.displayName(),
                    points = total.toPlainString(),
                    isPlaceholder = user?.placeholder ?: false,
                    isDeleted = user?.isDeleted() ?: false,
                )
            }
    return AwardedPointsSummaryResponse(
        rows = rows,
        totalPoints = byUser.values.fold(initial = BigDecimal.ZERO) { sum, value -> sum.add(value) }.toPlainString(),
    )
}
