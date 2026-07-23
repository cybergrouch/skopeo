// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.ranking

import kotlinx.serialization.Serializable
import org.skopeo.model.AdjustRankingPointCommand
import org.skopeo.model.GrantRankingPointCommand
import org.skopeo.model.PointClass
import org.skopeo.model.PointSourceType
import org.skopeo.model.RankingPointAward
import org.skopeo.service.ranking.RankingPointService
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

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
    val sourceType: String = PointSourceType.INTERNAL.name,
    val sourceId: String? = null,
    val band: String? = null,
    val reason: String? = null,
    val validFrom: String? = null,
    val validUntil: String? = null,
) {
    /** Parse into the domain command; throws [IllegalArgumentException] (→ 400) on a malformed field. */
    fun toCommand(userId: UUID): GrantRankingPointCommand =
        GrantRankingPointCommand(
            userId = userId,
            points = BigDecimal(points),
            pointClass = enumValueOf<PointClass>(name = pointClass),
            sourceType = enumValueOf<PointSourceType>(name = sourceType),
            sourceId = sourceId,
            band = band,
            reason = reason,
            validFrom = validFrom?.let { LocalDateTime.parse(it) },
            validUntil = validUntil?.let { LocalDateTime.parse(it) },
        )
}

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
) {
    /** Parse into the domain command; throws [IllegalArgumentException] (→ 400) on a malformed field. */
    fun toCommand(userId: UUID): AdjustRankingPointCommand =
        AdjustRankingPointCommand(
            userId = userId,
            points = BigDecimal(points),
            reason = reason,
            validFrom = LocalDateTime.parse(validFrom),
            validUntil = LocalDateTime.parse(validUntil),
        )
}

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

fun RankingPointService.AwardsPage.toResponse(): AwardedPointsPageResponse =
    AwardedPointsPageResponse(
        rows = rows.map { it.toRow() },
        total = total,
        limit = limit,
        offset = offset,
    )

private fun RankingPointService.ResolvedAward.toRow(): AwardedPointRow =
    AwardedPointRow(
        id = award.id.toString(),
        userId = award.userId.toString(),
        playerDisplayName = playerDisplayName,
        playerPublicCode = playerPublicCode,
        points = award.points.toPlainString(),
        pointClass = award.pointClass.name,
        band = award.band,
        sex = award.sex,
        sourceType = award.sourceType.name,
        source = resolvedSource(),
        matchPublicCode = matchPublicCode,
        eventPublicCode = eventPublicCode,
        reason = award.reason,
        grantedBy = award.grantedBy?.toString(),
        awardedAt = award.awardedAt.toString(),
        validFrom = award.validFrom.toString(),
        validUntil = award.validUntil.toString(),
        status = award.status.name,
        isPlaceholder = playerIsPlaceholder,
        isDeleted = playerIsDeleted,
    )

/** The display source: the granting match code, else the event code, else "manual"/"EXTERNAL". */
private fun RankingPointService.ResolvedAward.resolvedSource(): String =
    matchPublicCode
        ?: eventPublicCode
        ?: if (award.sourceType == PointSourceType.EXTERNAL) "EXTERNAL" else "manual"

fun RankingPointAward.toResponse(): RankingPointAwardResponse =
    RankingPointAwardResponse(
        id = id.toString(),
        userId = userId.toString(),
        points = points.toPlainString(),
        pointClass = pointClass.name,
        sourceType = sourceType.name,
        sourceId = sourceId,
        band = band,
        sex = sex,
        reason = reason,
        validFrom = validFrom.toString(),
        validUntil = validUntil.toString(),
        status = status.name,
        revokesAwardId = revokesAwardId?.toString(),
        grantedBy = grantedBy?.toString(),
        awardedAt = awardedAt.toString(),
        eventId = eventId?.toString(),
    )
