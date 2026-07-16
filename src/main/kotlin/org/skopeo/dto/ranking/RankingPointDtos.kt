// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.ranking

import kotlinx.serialization.Serializable
import org.skopeo.model.GrantRankingPointCommand
import org.skopeo.model.PointClass
import org.skopeo.model.PointSourceType
import org.skopeo.model.RankingPointAward
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
)

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
    )
