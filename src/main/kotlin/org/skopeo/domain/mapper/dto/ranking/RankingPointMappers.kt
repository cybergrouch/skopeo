// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.ranking

import org.skopeo.common.dto.ranking.AdjustRankingPointsRequest
import org.skopeo.common.dto.ranking.AwardedPointRow
import org.skopeo.common.dto.ranking.AwardedPointsPageResponse
import org.skopeo.common.dto.ranking.GrantRankingPointsRequest
import org.skopeo.common.dto.ranking.RankingPointAwardResponse
import org.skopeo.domain.model.AdjustRankingPointCommand
import org.skopeo.domain.model.AwardsPage
import org.skopeo.domain.model.GrantRankingPointCommand
import org.skopeo.domain.model.PointClass
import org.skopeo.domain.model.PointSourceType
import org.skopeo.domain.model.RankingPointAward
import org.skopeo.domain.model.ResolvedAward
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/** Parse into the domain command; throws [IllegalArgumentException] (→ 400) on a malformed field. */
fun GrantRankingPointsRequest.toCommand(userId: UUID): GrantRankingPointCommand =
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

/** Parse into the domain command; throws [IllegalArgumentException] (→ 400) on a malformed field. */
fun AdjustRankingPointsRequest.toCommand(userId: UUID): AdjustRankingPointCommand =
    AdjustRankingPointCommand(
        userId = userId,
        points = BigDecimal(points),
        reason = reason,
        validFrom = LocalDateTime.parse(validFrom),
        validUntil = LocalDateTime.parse(validUntil),
    )

fun AwardsPage.toResponse(): AwardedPointsPageResponse =
    AwardedPointsPageResponse(
        rows = rows.map { it.toRow() },
        total = total,
        limit = limit,
        offset = offset,
    )

private fun ResolvedAward.toRow(): AwardedPointRow =
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
private fun ResolvedAward.resolvedSource(): String =
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
