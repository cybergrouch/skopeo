// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.datetime

// NUMERIC precision/scale mirror the V13 schema (points to 4 decimal places).
private const val POINTS_PRECISION = 10
private const val POINTS_SCALE = 4
private const val POINT_CLASS_MAX = 32
private const val SOURCE_TYPE_MAX = 16
private const val SOURCE_ID_MAX = 64
private const val BAND_MAX = 8
private const val SEX_MAX = 16
private const val STATUS_MAX = 16

/**
 * Exposed mapping over the V13 ranking_point_awards ledger (#146). Append-only: rows are inserted on
 * grant and on revoke (a revoke also flips the original's status). Flyway owns the DDL; this maps
 * only what the repository touches. The `id` has no DB default, so it is set explicitly on insert.
 */
internal object RankingPointAwardsTable : UUIDTable(name = "ranking_point_awards") {
    val userId = reference(name = "user_id", foreign = UsersTable, onDelete = ReferenceOption.CASCADE)
    val points = decimal(name = "points", precision = POINTS_PRECISION, scale = POINTS_SCALE)
    val pointClass = varchar(name = "point_class", length = POINT_CLASS_MAX)
    val sourceType = varchar(name = "source_type", length = SOURCE_TYPE_MAX)
    val sourceId = varchar(name = "source_id", length = SOURCE_ID_MAX).nullable()
    val band = varchar(name = "band", length = BAND_MAX)
    val sex = varchar(name = "sex", length = SEX_MAX)
    val reason = text(name = "reason").nullable()
    val validFrom = datetime(name = "valid_from")
    val validUntil = datetime(name = "valid_until")
    val status = varchar(name = "status", length = STATUS_MAX)
    val revokesAwardId = uuid(name = "revokes_award_id").nullable()
    val grantedBy = reference(name = "granted_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val awardedAt = datetime(name = "awarded_at")

    // The event that produced this award on finalize (#403 Phase D, V18); null for manual/external grants.
    val eventId = reference(name = "event_id", foreign = EventsTable, onDelete = ReferenceOption.SET_NULL).nullable()
}
