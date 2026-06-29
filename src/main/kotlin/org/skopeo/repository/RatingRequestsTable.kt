// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.datetime

private const val STATUS_MAX = 20
private const val RATING_PRECISION = 10
private const val RATING_SCALE = 6

/** Re-rate requests (issue #140): a player's rating-reconsideration request and its resolution. */
internal object RatingRequestsTable : UUIDTable(name = "rating_requests") {
    val userId = reference(name = "user_id", foreign = UsersTable, onDelete = ReferenceOption.CASCADE)
    val justification = text(name = "justification")
    val status = varchar(name = "status", length = STATUS_MAX)
    val newRating = decimal(name = "new_rating", precision = RATING_PRECISION, scale = RATING_SCALE).nullable()
    val reason = text(name = "reason").nullable()
    val resolvedBy = reference(name = "resolved_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val resolvedAt = datetime(name = "resolved_at").nullable()
    val createdAt = datetime(name = "created_at")
}
