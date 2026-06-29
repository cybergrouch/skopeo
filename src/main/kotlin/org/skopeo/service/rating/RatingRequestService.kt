// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.rating

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditPersonRef
import org.skopeo.model.AuditWrite
import org.skopeo.model.Capability
import org.skopeo.model.RatingRequest
import org.skopeo.model.RatingRequestPage
import org.skopeo.model.RatingRequestStatus
import org.skopeo.model.RatingRequestView
import org.skopeo.model.ServiceError
import org.skopeo.model.displayName
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.RatingRequestRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.audit.AuditService
import org.skopeo.service.user.VerifiedFirebaseToken
import java.math.BigDecimal
import java.util.UUID

private const val MAX_PAGE_SIZE = 100

/**
 * Re-rate requests (issue #140, phase 2 of #106). A player raises one open request with a
 * justification; a RATER (or ADMINISTRATOR) approves it — applying a new rating via [RatingService],
 * which records the override + history — or denies it with a reason. Both resolutions are audited.
 *
 * Expected failures are returned as an [Either] left ([ServiceError], #115).
 */
class RatingRequestService(
    private val requests: RatingRequestRepository = RatingRequestRepository(),
    private val ratingService: RatingService = RatingService(),
    private val ratings: RatingRepository = RatingRepository(),
    private val users: UserRepository = UserRepository(),
    private val audit: AuditService = AuditService(),
) {
    /** A player raises a re-rate request (one open at a time, requires an existing rating). */
    fun create(
        token: VerifiedFirebaseToken,
        justification: String,
    ): Either<ServiceError, RatingRequest> =
        either {
            val caller = ensureNotNull(value = users.findByFirebaseUid(firebaseUid = token.uid)) { ServiceError.Forbidden() }
            ensure(condition = justification.isNotBlank()) { ServiceError.Validation(message = "A justification is required") }
            ensure(condition = ratings.findCurrentRating(userId = caller.id) != null) {
                ServiceError.Validation(message = "You don't have a rating to reconsider yet")
            }
            val request = requests.create(userId = caller.id, justification = justification.trim()).bind()
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = caller.id,
                        action = AuditAction.RATING_REREQUESTED,
                        entityType = AuditEntityType.RATING,
                        entityId = caller.id,
                        summary = "Requested a rating reconsideration",
                        details = mapOf("requestId" to request.id.toString(), "justification" to request.justification),
                    ),
            )
            request
        }

    /** The caller's most recent re-rate request (or null) — for the Profile tab. */
    fun mine(token: VerifiedFirebaseToken): Either<ServiceError, RatingRequest?> =
        either {
            val caller = ensureNotNull(value = users.findByFirebaseUid(firebaseUid = token.uid)) { ServiceError.Forbidden() }
            requests.findLatestByUser(userId = caller.id)
        }

    /** The re-rate triage list (RATER/ADMINISTRATOR), newest first, optionally filtered by status. */
    fun list(
        token: VerifiedFirebaseToken,
        limit: Int,
        offset: Int,
        status: RatingRequestStatus?,
    ): Either<ServiceError, RatingRequestPage> =
        either {
            requireRater(token = token).bind()
            val (items, total) =
                requests.list(
                    limit = limit.coerceIn(minimumValue = 1, maximumValue = MAX_PAGE_SIZE),
                    offset = offset.coerceAtLeast(minimumValue = 0),
                    status = status,
                )
            val refs = resolveRequesters(requests = items)
            RatingRequestPage(items = items.map { RatingRequestView(request = it, requester = refs[it.userId]) }, total = total.toInt())
        }

    /** Approve a pending request: apply [newRating] (RATING_OVERRIDDEN + history via RatingService), mark APPROVED. */
    fun approve(
        token: VerifiedFirebaseToken,
        id: UUID,
        newRating: BigDecimal,
    ): Either<ServiceError, RatingRequest> =
        either {
            val raterId = requireRater(token = token).bind()
            val request = ensureNotNull(value = requests.findById(id = id)) { ServiceError.NotFound(message = "Request $id not found") }
            // Atomically claim the request (PENDING → APPROVED); null means it was already resolved.
            val resolved =
                ensureNotNull(
                    value =
                        requests.resolve(
                            id = id,
                            status = RatingRequestStatus.APPROVED,
                            newRating = newRating,
                            reason = null,
                            resolvedBy = raterId,
                        ),
                ) { ServiceError.Conflict(message = "This request has already been resolved") }
            // Apply the new rating now that the resolution is ours (records the override + history).
            ratingService.setRating(token = token, userId = request.userId, value = newRating, confidence = null).bind()
            resolved
        }

    /** Deny a pending request with a reason (audited as RATING_REQUEST_DENIED). */
    fun deny(
        token: VerifiedFirebaseToken,
        id: UUID,
        reason: String,
    ): Either<ServiceError, RatingRequest> =
        either {
            val raterId = requireRater(token = token).bind()
            ensure(condition = reason.isNotBlank()) { ServiceError.Validation(message = "A reason is required to deny a request") }
            val request = ensureNotNull(value = requests.findById(id = id)) { ServiceError.NotFound(message = "Request $id not found") }
            // Atomically claim the request (PENDING → DENIED); null means it was already resolved.
            val resolved =
                ensureNotNull(
                    value =
                        requests.resolve(
                            id = id,
                            status = RatingRequestStatus.DENIED,
                            newRating = null,
                            reason = reason.trim(),
                            resolvedBy = raterId,
                        ),
                ) { ServiceError.Conflict(message = "This request has already been resolved") }
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = raterId,
                        action = AuditAction.RATING_REQUEST_DENIED,
                        entityType = AuditEntityType.RATING,
                        entityId = request.userId,
                        summary = "Denied a rating reconsideration",
                        details = mapOf("requestId" to id.toString(), "reason" to reason.trim()),
                    ),
            )
            resolved
        }

    private fun resolveRequesters(requests: List<RatingRequest>): Map<UUID, AuditPersonRef> =
        users
            .findAllByIds(ids = requests.map { it.userId }.distinct())
            .associate { it.id to AuditPersonRef(userId = it.id, displayName = it.displayName(), publicCode = it.publicCode) }

    private fun requireRater(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid) ?: return ServiceError.Forbidden().left()
        val canRate =
            caller.capabilities.contains(element = Capability.RATER) ||
                caller.capabilities.contains(element = Capability.ADMINISTRATOR)
        return if (canRate) caller.id.right() else ServiceError.Forbidden().left()
    }
}
