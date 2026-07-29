// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.invite

import kotlinx.serialization.Serializable

/** Body for `POST /api/v1/invites` — an administrator invites an email to onboard. */
@Serializable
data class CreateInviteRequest(
    val email: String,
)

@Serializable
data class InviteResponse(
    val id: String,
    val email: String,
    // PENDING | ACCEPTED | REVOKED | EXPIRED — EXPIRED is derived from expiresAt, not stored.
    val status: String,
    val invitedBy: String? = null,
    val expiresAt: String,
    val acceptedAt: String? = null,
    val createdAt: String,
)

@Serializable
data class InvitePageResponse(
    val items: List<InviteResponse>,
    val total: Int,
)
