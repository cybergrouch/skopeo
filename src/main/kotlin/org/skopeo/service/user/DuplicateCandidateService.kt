// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditWrite
import org.skopeo.model.Capability
import org.skopeo.model.DuplicateCandidate
import org.skopeo.model.DuplicateCandidateStatus
import org.skopeo.model.DuplicateCandidateView
import org.skopeo.model.DuplicateCandidateViewPage
import org.skopeo.model.DuplicateSignal
import org.skopeo.repository.DuplicateCandidateRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.ConflictException
import org.skopeo.service.ResourceNotFoundException
import org.skopeo.service.audit.AuditService
import java.time.LocalDateTime
import java.util.UUID

private const val MAX_PAGE_SIZE = 100

/**
 * The duplicate-candidate queue (issue #126) — ADMINISTRATOR-only. Candidates are raised automatically
 * (phone match, by [org.skopeo.service.contact.ContactService]) or manually here; an admin then either
 * dismisses a candidate or confirms it, which marks one account a duplicate of the other via the #124
 * tool ([DuplicateService.markDuplicates]). Every action is audit-logged.
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
        status: DuplicateCandidateStatus? = null,
    ): DuplicateCandidateViewPage {
        requireAdmin(token = token)
        val (items, total) =
            candidates.list(
                limit = limit.coerceIn(minimumValue = 1, maximumValue = MAX_PAGE_SIZE),
                offset = offset.coerceAtLeast(minimumValue = 0),
                status = status,
            )
        val byId = users.findAllByIds(ids = items.flatMap { listOf(it.userAId, it.userBId) }).associateBy { it.id }
        val views =
            items.map {
                DuplicateCandidateView(candidate = it, userA = byId.getValue(key = it.userAId), userB = byId.getValue(key = it.userBId))
            }
        return DuplicateCandidateViewPage(items = views, total = total.toInt())
    }

    /** Manually flag a suspected pair — the "complaint → investigation" path. */
    fun flagManual(
        token: VerifiedFirebaseToken,
        userAId: UUID,
        userBId: UUID,
        reason: String?,
    ): DuplicateCandidateView {
        val adminId = requireAdmin(token = token)
        require(value = userAId != userBId) { "A candidate needs two different users" }
        val userA = users.findById(id = userAId) ?: throw UserNotFoundException(id = userAId)
        val userB = users.findById(id = userBId) ?: throw UserNotFoundException(id = userBId)
        val candidate =
            candidates.flag(
                userAId = userAId,
                userBId = userBId,
                signal = DuplicateSignal.MANUAL,
                detail = reason,
                flaggedBy = adminId,
            )
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
        return DuplicateCandidateView(candidate = candidate, userA = userA, userB = userB)
    }

    /** Dismiss an open candidate (a false positive). */
    fun dismiss(
        token: VerifiedFirebaseToken,
        id: UUID,
    ) {
        val adminId = requireAdmin(token = token)
        val candidate = openCandidate(id = id)
        candidates.setStatus(id = id, status = DuplicateCandidateStatus.DISMISSED, resolvedBy = adminId, resolvedAt = LocalDateTime.now())
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
    ) {
        val adminId = requireAdmin(token = token)
        val candidate = openCandidate(id = id)
        require(value = canonicalId == candidate.userAId || canonicalId == candidate.userBId) {
            "The canonical account must be one of the candidate's two accounts"
        }
        val duplicateId = if (canonicalId == candidate.userAId) candidate.userBId else candidate.userAId
        duplicates.markDuplicates(token = token, canonicalId = canonicalId, duplicateIds = listOf(element = duplicateId))
        candidates.setStatus(id = id, status = DuplicateCandidateStatus.RESOLVED, resolvedBy = adminId, resolvedAt = LocalDateTime.now())
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

    private fun openCandidate(id: UUID): DuplicateCandidate {
        val candidate = candidates.findById(id = id) ?: throw ResourceNotFoundException(message = "No duplicate candidate $id")
        if (candidate.status != DuplicateCandidateStatus.OPEN) {
            throw ConflictException(message = "Candidate $id is already ${candidate.status.name.lowercase()}")
        }
        return candidate
    }

    private fun requireAdmin(token: VerifiedFirebaseToken): UUID {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        if (caller == null || !caller.capabilities.contains(element = Capability.ADMINISTRATOR)) throw ForbiddenException()
        return caller.id
    }
}
