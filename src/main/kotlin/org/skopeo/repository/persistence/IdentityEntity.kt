// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

/**
 * Raw persistence view of a `user_identities` row (#633): the dumb, as-stored data with **no behaviour**.
 * A flat aggregate — no child rows and no derived fields — so the corresponding domain
 * `org.skopeo.domain.model.UserIdentity` is a field-for-field copy, except [provider] is held as the RAW stored
 * `String` (the domain's `AuthProvider` enum is parsed at the conversion boundary, since `persistence` is
 * a leaf that must not import `model`). The repository loads a row into this, then converts it to the
 * domain `UserIdentity` at a single boundary (`IdentityEntity.toDomain`).
 */
data class IdentityEntity(
    val provider: String,
    val providerUid: String,
    val isPrimary: Boolean,
)
