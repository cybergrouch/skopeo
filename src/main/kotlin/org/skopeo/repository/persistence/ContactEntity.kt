// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of a `contact_information` row (#633): the dumb, as-stored data with **no
 * behaviour**. A flat aggregate — no child rows and no derived fields — so the corresponding domain
 * `org.skopeo.domain.model.Contact` is a field-for-field copy, except the enum fields ([type], [source],
 * [status], [method]) are held as the RAW stored `String` values and parsed at the conversion boundary
 * (since `persistence` is a leaf that must not import `model`). The repository maps a DB row to this,
 * then converts it to the domain `Contact` at a single boundary (`ContactEntity.toDomain`).
 */
data class ContactEntity(
    val id: UUID,
    val userId: UUID,
    val type: String,
    val value: String,
    val source: String,
    val status: String,
    val method: String?,
    val isPrimary: Boolean,
    val isActive: Boolean,
    val verifiedAt: LocalDateTime?,
    val verifiedBy: UUID?,
    val disabledAt: LocalDateTime?,
)
