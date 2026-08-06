// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.persistence

import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of an `invites` row (#633): the dumb, as-stored data with **no behaviour**.
 * A flat aggregate — no child rows — so the corresponding domain `org.skopeo.model.Invite` differs
 * only in that [status] is stored **raw as a `String`** here and parsed into the `InviteStatus` enum at
 * the domain boundary. Kept **model-free** (only stdlib types) so `persistence` stays a leaf package —
 * the repository maps a DB row to this, then converts it to the domain `Invite` at a single boundary
 * (`InviteEntity.toDomain`).
 */
data class InviteEntity(
    val id: UUID,
    val email: String,
    val status: String,
    val invitedBy: UUID?,
    val expiresAt: LocalDateTime,
    val acceptedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
)
