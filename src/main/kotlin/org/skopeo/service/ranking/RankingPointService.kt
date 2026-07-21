// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.ranking

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.skopeo.model.AdjustRankingPointCommand
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.AwardStatus
import org.skopeo.model.Capability
import org.skopeo.model.GrantRankingPointCommand
import org.skopeo.model.Level
import org.skopeo.model.PointClass
import org.skopeo.model.PointSourceType
import org.skopeo.model.RankingPointAward
import org.skopeo.model.RankingPointAwardWrite
import org.skopeo.model.ServiceError
import org.skopeo.model.displayName
import org.skopeo.repository.EventRepository
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import org.skopeo.service.user.VerifiedFirebaseToken
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

private const val DEFAULT_PAGE_SIZE = 25
private const val MAX_PAGE_SIZE = 100

/**
 * Admin-only management of the ranking-points ledger (#146, phase 1): grant, revoke, and list a
 * user's awards. Every award is **band-tagged at award time** (the design's decision B) — the band
 * defaults to the target's current NTRP band from their rating, overridable per grant; [sex] always
 * comes from the target user. Validity defaults from the [PointClass][org.skopeo.model.PointClass]
 * policy and is overridable. External / ad-hoc grants require a [reason] for traceability (§7).
 *
 * No snapshot/recompute here (that is #146 phase 2, which reuses #220); tournament auto-awards are
 * gated on the tournaments model (#390). Expected failures are an [Either] left ([ServiceError], #115).
 */
class RankingPointService(
    private val awards: RankingPointRepository = RankingPointRepository(),
    private val users: UserRepository = UserRepository(),
    private val ratings: RatingRepository = RatingRepository(),
    private val matches: MatchRepository = MatchRepository(),
    private val events: EventRepository = EventRepository(),
    private val audit: AuditService = AuditService(),
) {
    /**
     * One ledger row enriched for the Points Management list (#472): the raw [award] plus the player's
     * display name + public code and the granting source's public code (match, else event; null for a
     * manual / external grant). Names + codes are resolved once per page (batched), not per row.
     */
    data class ResolvedAward(
        val award: RankingPointAward,
        val playerDisplayName: String?,
        val playerPublicCode: String?,
        val matchPublicCode: String?,
        val eventPublicCode: String?,
        // True for a login-less, not-yet-claimed placeholder ("dummy") player (#496/#505), resolved from
        // the batched user lookup — the ledger renders an "Unclaimed" tag beside the name.
        val playerIsPlaceholder: Boolean = false,
    )

    /** One page of the whole ledger (#472): the resolved rows plus the full total for the pager. */
    data class AwardsPage(
        val rows: List<ResolvedAward>,
        val total: Int,
        val limit: Int,
        val offset: Int,
    )

    /** Grant an award to a user. ADMINISTRATOR-only; band-tagged, sex from the target, policy validity. */
    fun grant(
        token: VerifiedFirebaseToken,
        command: GrantRankingPointCommand,
    ): Either<ServiceError, RankingPointAward> =
        either {
            val adminId = requireAdmin(token = token).bind()
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
                ).mapLeft { ServiceError.Validation(message = "Unknown user ${command.userId}") }.bind()
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
                )
            audit.record(write = grantAudit(actorId = adminId, award = award))
            award
        }

    /**
     * A manual, signed point adjustment (#469): an admin awards (+) or deducts (−) points for a player
     * from the Manage Player section. It writes a non-budgeted EXTERNAL ledger entry via [award], reusing
     * the same audit provenance as [grant] (target = the player, #471). Differs from [grant] in three
     * ways: [points] are **signed** (positive or negative, non-zero, whole), the [reason] is **always
     * required**, and the validity window is **explicit** (no point-class default). Band = the player's
     * current band (an unrated player has nothing to tag → Validation); sex from the player. It is queued:
     * the next [StandingsCalculationService][org.skopeo.service.standings.StandingsCalculationService]
     * recompute sums it (a normal ACTIVE, in-window award), while the live points audit (#448) shows it now.
     */
    fun adjust(
        token: VerifiedFirebaseToken,
        command: AdjustRankingPointCommand,
    ): Either<ServiceError, RankingPointAward> =
        either {
            val adminId = requireAdmin(token = token).bind()
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
                ).mapLeft { ServiceError.Validation(message = "Unknown user ${command.userId}") }.bind()
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
                )
            audit.record(write = grantAudit(actorId = adminId, award = award))
            award
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
    ): Either<ServiceError, List<RankingPointAward>> =
        either {
            requireAdmin(token = token).bind()
            users.findById(id = userId).mapLeft { ServiceError.NotFound(message = "User $userId not found") }.bind()
            awards.listByUser(userId = userId)
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
    ): Either<ServiceError, AwardsPage> =
        either {
            requirePointsManager(token = token).bind()
            val pageSize = (limit ?: DEFAULT_PAGE_SIZE).coerceIn(minimumValue = 1, maximumValue = MAX_PAGE_SIZE)
            val pageOffset = (offset ?: 0).coerceAtLeast(minimumValue = 0)
            val (rows, total) = awards.listAwards(limit = pageSize, offset = pageOffset)

            val usersById = users.findAllByIds(ids = rows.map { it.userId }).associateBy { it.id }
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
                    )
                }
            AwardsPage(rows = resolved, total = total.toInt(), limit = pageSize, offset = pageOffset)
        }

    /** The target's current NTRP band label (e.g. "4.0"), or null when they have no rating yet. */
    private fun currentBand(userId: UUID): String? {
        val rating = ratings.findCurrentRating(userId = userId) ?: return null
        return Level.fromValue(value = rating.currentRating.toPlainString()).value
    }

    /** ADMINISTRATOR-only access; returns the caller's id (the audit actor). */
    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
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
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        val allowed =
            caller != null &&
                caller.capabilities.any { it == Capability.ADMINISTRATOR || it == Capability.POINTS_MANAGER }
        return if (caller == null || !allowed) ServiceError.Forbidden().left() else caller.id.right()
    }
}
