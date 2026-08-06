// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.user

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import org.skopeo.common.dto.duplicate.DuplicateCandidatePageResponse
import org.skopeo.common.dto.duplicate.DuplicateCandidateResponse
import org.skopeo.common.error.ServiceError
import org.skopeo.common.security.Capability
import org.skopeo.domain.mapper.dto.duplicate.toResponse
import org.skopeo.domain.mapper.entity.user.toDomain
import org.skopeo.domain.model.AuditAction
import org.skopeo.domain.model.AuditEntityType
import org.skopeo.domain.model.AuditWrite
import org.skopeo.domain.model.DuplicateCandidate
import org.skopeo.domain.model.DuplicateCandidateStatus
import org.skopeo.domain.model.DuplicateCandidateView
import org.skopeo.domain.model.DuplicateCandidateViewPage
import org.skopeo.domain.model.DuplicateSignal
import org.skopeo.domain.service.audit.AuditService
import org.skopeo.repository.DuplicateCandidateRepository
import org.skopeo.repository.UserRepository
import java.time.LocalDateTime
import java.util.UUID

private const val MAX_PAGE_SIZE = 100

/**
 * The duplicate-candidate queue (issue #126) — ADMINISTRATOR-only. Candidates are raised automatically
 * (phone match, by [org.skopeo.domain.service.contact.ContactService]) or manually here; an admin then either
 * dismisses a candidate or confirms it, which marks one account a duplicate of the other via the #124
 * tool ([DuplicateService.markDuplicates]). Every action is audit-logged.
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
class DuplicateCandidateService(
    private val candidates: DuplicateCandidateRepository = DuplicateCandidateRepository(),
    private val users: UserRepository = UserRepository(),
    private val duplicates: DuplicateService = DuplicateService(),
    private val audit: AuditService = AuditService(),
) {
    fun list(
        token: VerifiedFirebaseToken,
        limit: Int,
        offset: Int,
        statusRaw: String?,
    ): Either<ServiceError, DuplicateCandidatePageResponse> =
        either {
            requireAdmin(token = token).bind()
            val status = statusRaw?.let { raw -> parseStatus(raw = raw).bind() }
            val (items, total) =
                candidates.list(
                    limit = limit.coerceIn(minimumValue = 1, maximumValue = MAX_PAGE_SIZE),
                    offset = offset.coerceAtLeast(minimumValue = 0),
                    status = status,
                )
            val byId =
                users.findAllByIds(ids = items.flatMap { listOf(it.userAId, it.userBId) }).map { it.toDomain() }.associateBy { it.id }
            val views =
                items.map {
                    DuplicateCandidateView(
                        candidate = it.toDomain(),
                        userA = byId.getValue(key = it.userAId),
                        userB = byId.getValue(key = it.userBId),
                        userADeleted = byId.getValue(key = it.userAId).isDeleted(),
                        userBDeleted = byId.getValue(key = it.userBId).isDeleted(),
                    )
                }
            DuplicateCandidateViewPage(items = views, total = total.toInt()).toResponse()
        }

    /** Manually flag a suspected pair — the "complaint → investigation" path. */
    fun flagManual(
        token: VerifiedFirebaseToken,
        userAId: UUID,
        userBId: UUID,
        reason: String?,
    ): Either<ServiceError, DuplicateCandidateResponse> =
        either {
            val adminId = requireAdmin(token = token).bind()
            ensure(condition = userAId != userBId) { ServiceError.Validation(message = "A candidate needs two different users") }
            val userA = users.findById(id = userAId).bind().toDomain()
            val userB = users.findById(id = userBId).bind().toDomain()
            val candidate =
                candidates.flag(
                    userAId = userAId,
                    userBId = userBId,
                    signal = DuplicateSignal.MANUAL,
                    detail = reason,
                    flaggedBy = adminId,
                ).toDomain()
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.DUPLICATE_CANDIDATE_FLAGGED,
                        entityType = AuditEntityType.USER,
                        entityId = candidate.id,
                        summary = "Flagged ${userA.publicCode} & ${userB.publicCode} as possible duplicates",
                        details = mapOf("userAId" to userAId.toString(), "userBId" to userBId.toString()),
                    ),
            )
            DuplicateCandidateView(
                candidate = candidate,
                userA = userA,
                userB = userB,
                userADeleted = userA.isDeleted(),
                userBDeleted = userB.isDeleted(),
            ).toResponse()
        }

    /** Dismiss an open candidate (a false positive). */
    fun dismiss(
        token: VerifiedFirebaseToken,
        id: UUID,
    ): Either<ServiceError, Unit> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val candidate = openCandidate(id = id).bind()
            candidates
                .setStatus(id = id, status = DuplicateCandidateStatus.DISMISSED, resolvedBy = adminId, resolvedAt = LocalDateTime.now())
                .bind()
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.DUPLICATE_CANDIDATE_DISMISSED,
                        entityType = AuditEntityType.USER,
                        entityId = candidate.id,
                        summary = "Dismissed duplicate candidate ${candidate.id}",
                    ),
            )
        }

    /**
     * Confirm a candidate: [canonicalId] must be one of its two accounts; the other is marked a disabled
     * duplicate via the #124 tool, and the candidate is resolved.
     */
    fun confirm(
        token: VerifiedFirebaseToken,
        id: UUID,
        canonicalId: UUID,
    ): Either<ServiceError, Unit> =
        either {
            val adminId = requireAdmin(token = token).bind()
            val candidate = openCandidate(id = id).bind()
            ensure(condition = canonicalId == candidate.userAId || canonicalId == candidate.userBId) {
                ServiceError.Validation(message = "The canonical account must be one of the candidate's two accounts")
            }
            val duplicateId = if (canonicalId == candidate.userAId) candidate.userBId else candidate.userAId
            duplicates.markDuplicates(token = token, canonicalId = canonicalId, duplicateIds = listOf(element = duplicateId)).bind()
            candidates
                .setStatus(id = id, status = DuplicateCandidateStatus.RESOLVED, resolvedBy = adminId, resolvedAt = LocalDateTime.now())
                .bind()
            audit.record(
                write =
                    AuditWrite(
                        actorUserId = adminId,
                        action = AuditAction.DUPLICATE_CANDIDATE_CONFIRMED,
                        entityType = AuditEntityType.USER,
                        entityId = candidate.id,
                        summary = "Confirmed duplicate candidate ${candidate.id}",
                        details = mapOf("canonicalUserId" to canonicalId.toString(), "duplicateUserId" to duplicateId.toString()),
                    ),
            )
        }

    private fun parseStatus(raw: String): Either<ServiceError, DuplicateCandidateStatus> {
        val allowed = DuplicateCandidateStatus.entries.joinToString { it.name }
        return DuplicateCandidateStatus.entries.find { it.name == raw.uppercase() }?.right()
            ?: ServiceError.Validation(message = "Unknown status '$raw'; expected one of $allowed").left()
    }

    private fun openCandidate(id: UUID): Either<ServiceError, DuplicateCandidate> =
        either {
            val candidate = candidates.findById(id = id).bind().toDomain()
            ensure(condition = candidate.status == DuplicateCandidateStatus.OPEN) {
                ServiceError.Conflict(message = "Candidate $id is already ${candidate.status.name.lowercase()}")
            }
            candidate
        }

    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, UUID> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)?.toDomain()
        return if (caller == null || !caller.capabilities.contains(element = Capability.ADMINISTRATOR)) {
            ServiceError.Forbidden().left()
        } else {
            caller.id.right()
        }
    }
}
