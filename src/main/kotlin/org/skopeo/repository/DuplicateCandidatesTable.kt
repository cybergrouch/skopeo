// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.datetime

private const val DUP_CODE_MAX = 20

/**
 * Exposed mapping over the duplicate_candidates table (issue #126). The suspected pair is stored ordered
 * (user_a_id < user_b_id); a partial unique index keeps at most one OPEN candidate per pair.
 */
internal object DuplicateCandidatesTable : UUIDTable(name = "duplicate_candidates") {
    val userAId = reference(name = "user_a_id", foreign = UsersTable, onDelete = ReferenceOption.CASCADE)
    val userBId = reference(name = "user_b_id", foreign = UsersTable, onDelete = ReferenceOption.CASCADE)
    val signal = varchar(name = "signal", length = DUP_CODE_MAX)
    val detail = text(name = "detail").nullable()
    val status = varchar(name = "status", length = DUP_CODE_MAX).default(defaultValue = "OPEN")
    val flaggedBy = reference(name = "flagged_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val flaggedAt = datetime(name = "flagged_at")
    val resolvedBy = reference(name = "resolved_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val resolvedAt = datetime(name = "resolved_at").nullable()
}
