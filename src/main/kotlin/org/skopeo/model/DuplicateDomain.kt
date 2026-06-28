// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.time.LocalDateTime
import java.util.UUID

/** How a duplicate candidate was raised (issue #126): an automatic phone match, or an admin's manual flag. */
enum class DuplicateSignal { DUPLICATE_PHONE, MANUAL }

/** Lifecycle of a duplicate candidate: OPEN until an admin confirms (→ RESOLVED) or dismisses it. */
enum class DuplicateCandidateStatus { OPEN, DISMISSED, RESOLVED }

/**
 * A suspected pair of duplicate accounts surfaced for ADMINISTRATOR review (issue #126). Detection never
 * disables anyone — it only flags. The pair is stored ordered ([userAId] < [userBId]) so the same two
 * accounts collapse to one candidate regardless of order. [flaggedBy] is null for a system (automatic)
 * flag. Confirming resolves it by marking one account a duplicate of the other via the #124 tool.
 */
data class DuplicateCandidate(
    val id: UUID,
    val userAId: UUID,
    val userBId: UUID,
    val signal: DuplicateSignal,
    val detail: String? = null,
    val status: DuplicateCandidateStatus,
    val flaggedBy: UUID? = null,
    val flaggedAt: LocalDateTime,
    val resolvedBy: UUID? = null,
    val resolvedAt: LocalDateTime? = null,
)

/** A page of duplicate candidates plus the total matching the filter (for the admin queue). */
data class DuplicateCandidatePage(
    val items: List<DuplicateCandidate>,
    val total: Int,
)

/** A candidate with its two suspected accounts resolved, for rendering the admin queue. */
data class DuplicateCandidateView(
    val candidate: DuplicateCandidate,
    val userA: User,
    val userB: User,
)

/** A page of resolved candidate views plus the total (for the admin queue). */
data class DuplicateCandidateViewPage(
    val items: List<DuplicateCandidateView>,
    val total: Int,
)
