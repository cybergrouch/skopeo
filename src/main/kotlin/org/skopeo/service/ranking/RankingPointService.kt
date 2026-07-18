// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.ranking

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.AwardStatus
import org.skopeo.model.Capability
import org.skopeo.model.GrantRankingPointCommand
import org.skopeo.model.Level
import org.skopeo.model.PointSourceType
import org.skopeo.model.RankingPointAward
import org.skopeo.model.RankingPointAwardWrite
import org.skopeo.model.ServiceError
import org.skopeo.repository.RankingPointRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import org.skopeo.service.user.VerifiedFirebaseToken
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

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
    private val audit: AuditService = AuditService(),
) {
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
}
