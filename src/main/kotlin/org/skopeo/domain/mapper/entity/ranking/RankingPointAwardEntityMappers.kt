// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.entity.ranking

import org.skopeo.domain.model.AwardStatus
import org.skopeo.domain.model.PointClass
import org.skopeo.domain.model.PointSourceType
import org.skopeo.domain.model.RankingPointAward
import org.skopeo.repository.persistence.RankingPointAwardEntity

/**
 * Entity→domain mapper (#633): builds the domain [RankingPointAward] from the raw persistence
 * [RankingPointAwardEntity] the repository returns. The three enum-backed columns ([pointClass],
 * [sourceType], [status]) are held RAW as their stored `String` values on the entity and parsed into
 * their model enums here — the single boundary at which the ledger row becomes a domain object. Lives
 * in `mapper.entity` (which may depend on both `persistence` and `model`); the service calls it, since
 * `repository ↛ mapper`.
 */
fun RankingPointAwardEntity.toDomain(): RankingPointAward =
    RankingPointAward(
        id = id,
        userId = userId,
        points = points,
        pointClass = PointClass.valueOf(value = pointClass),
        sourceType = PointSourceType.valueOf(value = sourceType),
        sourceId = sourceId,
        band = band,
        sex = sex,
        reason = reason,
        validFrom = validFrom,
        validUntil = validUntil,
        status = AwardStatus.valueOf(value = status),
        revokesAwardId = revokesAwardId,
        grantedBy = grantedBy,
        awardedAt = awardedAt,
        eventId = eventId,
        matchId = matchId,
        pointsScheduleVersion = pointsScheduleVersion,
        teamBand = teamBand,
        opponentBand = opponentBand,
    )
