// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.points

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.Capability
import org.skopeo.model.ClubBudgetView
import org.skopeo.model.EventType
import org.skopeo.model.PointsPolicy
import org.skopeo.model.ServiceError
import org.skopeo.repository.ClubRepository
import org.skopeo.repository.PointsBudgetRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import org.skopeo.service.user.VerifiedFirebaseToken
import java.util.UUID

/**
 * Points-budget management (#403): the global master policy and per-club per-type budgets. Writes are
 * points-manager-gated ([requirePointsManager]) — ADMINISTRATOR is implicitly a points manager (§5.1).
 * As of Phase D the accounting reflects real utilization: [allocatedFor] returns the EMERGENT
 * reservation (summed fixture designations of non-finalized events) PLUS the active awards of the
 * club's finalized events (the reserve→award transition), and Free = Budgeted − Allocated.
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
class PointsBudgetService(
    private val budgets: PointsBudgetRepository = PointsBudgetRepository(),
    private val clubs: ClubRepository = ClubRepository(),
    private val users: UserRepository = UserRepository(),
    private val audit: AuditService = AuditService(),
) {
    /** The global per-type policies. Readable by staff (points managers / administrators). */
    fun policies(token: VerifiedFirebaseToken): Either<ServiceError, List<PointsPolicy>> =
        either {
            requirePointsManager(token = token).bind()
            budgets.listPolicies()
        }

    /** Set the global policy for one event type (points-manager). Validates min ≤ max and all > 0. */
    fun setPolicy(
        token: VerifiedFirebaseToken,
        policy: PointsPolicy,
    ): Either<ServiceError, PointsPolicy> =
        either {
            val actorId = requirePointsManager(token = token).bind()
            ensure(condition = policy.minPoints > 0) { ServiceError.Validation(message = "Min points must be greater than zero") }
            ensure(condition = policy.maxPoints > 0) { ServiceError.Validation(message = "Max points must be greater than zero") }
            ensure(condition = policy.maxValidityDays > 0) {
                ServiceError.Validation(message = "Max validity days must be greater than zero")
            }
            ensure(condition = policy.minPoints <= policy.maxPoints) {
                ServiceError.Validation(message = "Min points must not exceed max points")
            }
            val saved = budgets.upsertPolicy(policy = policy)
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = actorId,
                        action = AuditAction.POINTS_POLICY_UPDATED,
                        entityType = AuditEntityType.POINTS_BUDGET,
                        entityId = null,
                        summary = "Set ${saved.eventType} points policy to ${saved.minPoints}-${saved.maxPoints}",
                        details =
                            mapOf(
                                "eventType" to saved.eventType.name,
                                "minPoints" to saved.minPoints.toString(),
                                "maxPoints" to saved.maxPoints.toString(),
                                "maxValidityDays" to saved.maxValidityDays.toString(),
                            ),
                    ),
            )
            saved
        }

    /**
     * The per club × event-type accounting ([ClubBudgetView]s), optionally scoped to one [clubId]
     * (points-manager). [ClubBudgetView.allocated] is [allocatedFor] (0 now) and free = budgeted −
     * allocated. Every event type is surfaced for each club, even those with no budget row yet (0).
     */
    fun clubBudgets(
        token: VerifiedFirebaseToken,
        clubId: UUID?,
    ): Either<ServiceError, List<ClubBudgetView>> =
        either {
            requirePointsManager(token = token).bind()
            val clubIds =
                if (clubId != null) {
                    ensureNotNull(value = clubs.findById(id = clubId)) { ServiceError.NotFound(message = "Club $clubId not found") }
                    listOf(element = clubId)
                } else {
                    clubs.list().map { it.id }
                }
            val budgeted =
                (if (clubId != null) budgets.listBudgetsForClub(clubId = clubId) else budgets.listBudgets())
                    .associateBy { it.clubId to it.eventType }
            clubIds.flatMap { id ->
                EventType.entries.map { type ->
                    val amount = budgeted[id to type]?.budgetedPoints ?: 0
                    val allocated = allocatedFor(clubId = id, eventType = type)
                    ClubBudgetView(clubId = id, eventType = type, budgeted = amount, allocated = allocated, free = amount - allocated)
                }
            }
        }

    /** Set a club's budget for one event type (points-manager). Validates points ≥ 0 and the club exists. */
    fun setClubBudget(
        token: VerifiedFirebaseToken,
        clubId: UUID,
        eventType: EventType,
        points: Int,
    ): Either<ServiceError, ClubBudgetView> =
        either {
            val actorId = requirePointsManager(token = token).bind()
            ensure(condition = points >= 0) { ServiceError.Validation(message = "Budget must not be negative") }
            ensureNotNull(value = clubs.findById(id = clubId)) { ServiceError.NotFound(message = "Club $clubId not found") }
            val saved = budgets.upsertBudget(clubId = clubId, eventType = eventType, budgetedPoints = points, updatedBy = actorId)
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = actorId,
                        action = AuditAction.POINTS_BUDGET_ALLOCATED,
                        entityType = AuditEntityType.POINTS_BUDGET,
                        entityId = clubId,
                        summary = "Set $eventType budget to $points for club $clubId",
                        details =
                            mapOf(
                                "clubId" to clubId.toString(),
                                "eventType" to eventType.name,
                                "budgetedPoints" to points.toString(),
                            ),
                    ),
            )
            val allocated = allocatedFor(clubId = clubId, eventType = eventType)
            ClubBudgetView(
                clubId = clubId,
                eventType = eventType,
                budgeted = saved.budgetedPoints,
                allocated = allocated,
                free = saved.budgetedPoints - allocated,
            )
        }

    /**
     * Points a club currently has in use for [eventType] = reservations (Phase C) + active awards
     * (Phase D). Reservations are EMERGENT — [PointsBudgetRepository.sumReservedPoints] sums the
     * designations (× team size) of the club's active, NON-finalized fixtures of this type; voiding a
     * fixture drops it from the sum. [PointsBudgetRepository.sumActiveAwards] sums the ACTIVE, in-window
     * awards linked to this club's FINALIZED events of this type. The two are mutually exclusive on
     * finalize (a fixture leaves Reserved as its event finalizes and reappears as an Awarded ledger
     * row), so Allocated stays continuous across finalize — no double counting, no gap.
     */
    private fun allocatedFor(
        clubId: UUID,
        eventType: EventType,
    ): Int =
        budgets.sumReservedPoints(clubId = clubId, eventType = eventType) +
            budgets.sumActiveAwards(clubId = clubId, eventType = eventType)

    /**
     * Points-manager access (§5.1): ADMINISTRATOR is implicitly a points manager, so the caller
     * passes as an ADMINISTRATOR or a POINTS_MANAGER; returns their id (the audit actor).
     */
    private fun requirePointsManager(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        val allowed =
            caller != null &&
                caller.capabilities.any { it == Capability.ADMINISTRATOR || it == Capability.POINTS_MANAGER }
        return if (caller == null || !allowed) ServiceError.Forbidden().left() else caller.id.right()
    }
}
