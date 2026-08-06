// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of a `users` row (#633 proof): the dumb, as-stored data with **no derived fields
 * and no behaviour** — contrast the domain `org.skopeo.model.User`, which additionally carries the
 * *derived* `photoUrl` (see `effectivePhotoUrl`) and the assembled name/contact/identity/capability
 * sub-objects. This is the "entity/data model" half of the entity ⟷ domain split: it encapsulates the
 * raw row so the domain model no longer does double duty as both persistence type and service type.
 *
 * Kept **model-free** (only stdlib types) so `persistence` stays a leaf package — the repository maps a
 * DB row to this, then converts it to the domain `User` at a single boundary (`UserEntity.toDomain`).
 * Scope note (#633): this proof entity-ifies only the top-level `users` row, where the sole raw-vs-domain
 * difference lives (the derived photo). The child rows (names/contacts/identities) have no derivations,
 * so they stay domain and are attached during conversion; a full rollout would add child entities too.
 */
data class UserEntity(
    val id: UUID,
    val publicCode: String,
    val firebaseUid: String?,
    // Raw photo state — the domain's displayed `photoUrl` is derived from these three, not stored here.
    val providerPhotoUrl: String?,
    val customPhotoUrl: String?,
    val photoHidden: Boolean,
    val matchHistoryHidden: Boolean,
    val dateOfBirth: LocalDate?,
    val sex: String?,
    val city: String?,
    val country: String,
    val kycVerified: Boolean,
    val isActive: Boolean,
    val proposedRating: BigDecimal?,
    val canonicalUserId: UUID?,
    val placeholder: Boolean,
    val claimedAt: LocalDateTime?,
    val claimedBy: UUID?,
    val previewRatingsAsNonAdmin: Boolean,
)
