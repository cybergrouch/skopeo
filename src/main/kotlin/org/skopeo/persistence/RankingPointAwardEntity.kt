// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.persistence

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of a `ranking_point_awards` row (#633): the dumb, as-stored data with **no
 * behaviour**. Kept **model-free** (only stdlib types) so `persistence` stays a leaf package — the
 * repository maps a DB row to this, then converts it to the domain `org.skopeo.model.RankingPointAward`
 * at a single boundary (`RankingPointAwardEntity.toDomain`). The three enum-backed columns
 * ([pointClass], [sourceType], [status]) are held RAW as their stored `String` values here and parsed
 * into their model enums (`PointClass`/`PointSourceType`/`AwardStatus`) only in `toDomain`.
 */
data class RankingPointAwardEntity(
    val id: UUID,
    val userId: UUID,
    val points: BigDecimal,
    // Raw stored String for the domain enum PointClass — parsed in toDomain.
    val pointClass: String,
    // Raw stored String for the domain enum PointSourceType — parsed in toDomain.
    val sourceType: String,
    val sourceId: String?,
    val band: String,
    val sex: String,
    val reason: String?,
    val validFrom: LocalDateTime,
    val validUntil: LocalDateTime,
    // Raw stored String for the domain enum AwardStatus — parsed in toDomain.
    val status: String,
    val revokesAwardId: UUID?,
    val grantedBy: UUID?,
    val awardedAt: LocalDateTime,
    val eventId: UUID?,
    val matchId: UUID?,
)
