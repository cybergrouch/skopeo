// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.time.LocalDateTime
import java.util.UUID

/** Stored lifecycle of an invite; EXPIRED is derived from [Invite.expiresAt], not stored. */
enum class InviteStatus { PENDING, ACCEPTED, REVOKED }

/**
 * An admin invitation to onboard a manual (email/password & email-link) member (issue #74). An invite
 * is "open" when it is PENDING and not past [expiresAt]; only an open invite admits provisioning.
 */
data class Invite(
    val id: UUID,
    val email: String,
    val status: InviteStatus,
    val invitedBy: UUID?,
    val expiresAt: LocalDateTime,
    val acceptedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime,
) {
    /** True when the invite still admits sign-up: PENDING and not yet past its expiry. */
    fun isOpen(asOf: LocalDateTime): Boolean = status == InviteStatus.PENDING && expiresAt.isAfter(asOf)
}

/** A page of invites plus the total count of all invites (for the admin list). */
data class InvitePage(
    val items: List<Invite>,
    val total: Int,
)
