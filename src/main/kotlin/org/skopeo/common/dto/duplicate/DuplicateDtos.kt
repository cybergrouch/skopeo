// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.dto.duplicate

import kotlinx.serialization.Serializable
import org.skopeo.common.dto.user.UserSummaryResponse

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
