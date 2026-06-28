// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.duplicate

import kotlinx.serialization.Serializable
import org.skopeo.dto.user.UserSummaryResponse
import org.skopeo.dto.user.toSummary
import org.skopeo.model.DuplicateCandidateView
import org.skopeo.model.DuplicateCandidateViewPage

/** Body for `POST /api/v1/duplicate-candidates` — an admin manually flags a suspected pair (#126). */
@Serializable
data class FlagCandidateRequest(
    val userAId: String,
    val userBId: String,
    val reason: String? = null,
)

/** Body for `POST /api/v1/duplicate-candidates/{id}/confirm` — which account is the canonical ("true") one. */
@Serializable
data class ConfirmCandidateRequest(
    val canonicalId: String,
)

/** A duplicate candidate for the admin queue: the signal that raised it plus both suspected accounts. */
@Serializable
data class DuplicateCandidateResponse(
    val id: String,
    val status: String,
    val signal: String,
    val detail: String?,
    val flaggedAt: String,
    val userA: UserSummaryResponse,
    val userB: UserSummaryResponse,
)

@Serializable
data class DuplicateCandidatePageResponse(
    val items: List<DuplicateCandidateResponse>,
    val total: Int,
)

fun DuplicateCandidateView.toResponse(): DuplicateCandidateResponse =
    DuplicateCandidateResponse(
        id = candidate.id.toString(),
        status = candidate.status.name,
        signal = candidate.signal.name,
        detail = candidate.detail,
        flaggedAt = candidate.flaggedAt.toString(),
        userA = userA.toSummary(),
        userB = userB.toSummary(),
    )

fun DuplicateCandidateViewPage.toResponse(): DuplicateCandidatePageResponse =
    DuplicateCandidatePageResponse(items = items.map { it.toResponse() }, total = total)
