// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.dto.ranking

import kotlinx.serialization.Serializable

/**
 * Body for `POST /api/v1/users/{userId}/ranking-points` — an administrator grants a ranking-point
 * award (#146). [points] is a decimal string. [band] and [validUntil]/[validFrom] are optional
 * overrides (band defaults to the target's current band; validity from the point-class policy).
 * [pointClass]/[sourceType] are parsed and validated into commands here (a bad value → 400).
 */
@Serializable
data class GrantRankingPointsRequest(
    val points: String,
    val pointClass: String,
    // Defaults to PointSourceType.INTERNAL.name; the mapper parses/validates it into the domain command.
    val sourceType: String = "INTERNAL",
    val sourceId: String? = null,
    val band: String? = null,
    val reason: String? = null,
    val validFrom: String? = null,
    val validUntil: String? = null,
)

/**
 * Body for `POST /api/v1/users/{userId}/ranking-points/adjustments` — an administrator manually awards
 * (+) or deducts (−) points for a player (#469). All fields are mandatory: [points] is a **signed**
 * integer as a decimal string (non-zero, whole; the service enforces the numeric rules), [reason] is the
 * comment / rationale, and [validFrom]/[validUntil] are the explicit validity window (ISO date-times).
 */
@Serializable
data class AdjustRankingPointsRequest(
    val points: String,
    val reason: String,
    val validFrom: String,
    val validUntil: String,
)

/** Body for `POST /api/v1/ranking-points/{awardId}/revoke` — an optional reason for the revocation. */
@Serializable
data class RevokeRankingPointsRequest(
    val reason: String? = null,
)

/** One ledger row as returned to clients (#146). Timestamps and points are ISO/decimal strings. */
@Serializable
data class RankingPointAwardResponse(
    val id: String,
    val userId: String,
    val points: String,
    val pointClass: String,
    val sourceType: String,
    val sourceId: String? = null,
    val band: String,
    val sex: String,
    val reason: String? = null,
    val validFrom: String,
    val validUntil: String,
    val status: String,
    val revokesAwardId: String? = null,
    val grantedBy: String? = null,
    val awardedAt: String,
    // The event that produced this award on finalize (#403 Phase D); null for manual / external grants.
    val eventId: String? = null,
)

/**
 * One row of the Points Management "Points awarded" list (#472): the ledger row plus the resolved
 * player identity (id + display name + public code) and the granting [source] — the match public
 * code, else the event public code, else "manual" (for an INTERNAL grant) or "EXTERNAL". Timestamps
 * and points are ISO/decimal strings, mirroring [RankingPointAwardResponse].
 */
@Serializable
data class AwardedPointRow(
    val id: String,
    val userId: String,
    val playerDisplayName: String? = null,
    val playerPublicCode: String? = null,
    val points: String,
    val pointClass: String,
    val band: String,
    val sex: String,
    val sourceType: String,
    // The granting match/event public code if present, else "manual" (INTERNAL) or "EXTERNAL".
    val source: String,
    val matchPublicCode: String? = null,
    val eventPublicCode: String? = null,
    val reason: String? = null,
    val grantedBy: String? = null,
    val awardedAt: String,
    val validFrom: String,
    val validUntil: String,
    val status: String,
    // True for a login-less, not-yet-claimed placeholder ("dummy") player (#496/#505): the points ledger
    // renders an "Unclaimed" tag beside the name. Real/claimed players leave it false.
    val isPlaceholder: Boolean = false,
    // True for an admin-soft-deleted account (#518): the points ledger renders a dominant "Deleted" chip.
    val isDeleted: Boolean = false,
)

/** A page of the ledger (#472) mirroring the standings page shape: rows + total + window. */
@Serializable
data class AwardedPointsPageResponse(
    val rows: List<AwardedPointRow>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)
