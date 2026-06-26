// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.datetime

private const val INVITE_EMAIL_MAX = 255
private const val INVITE_STATUS_MAX = 20

/**
 * Exposed mapping over the V5 invites table (issue #74). created_at is DB-managed (default), so it is
 * read-only here — never set on insert — used only to order the admin list newest-first.
 */
internal object InvitesTable : UUIDTable(name = "invites") {
    val email = varchar(name = "email", length = INVITE_EMAIL_MAX)
    val status = varchar(name = "status", length = INVITE_STATUS_MAX).default(defaultValue = "PENDING")
    val invitedBy = reference(name = "invited_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val expiresAt = datetime(name = "expires_at")
    val acceptedAt = datetime(name = "accepted_at").nullable()
    val createdAt = datetime(name = "created_at")
}
