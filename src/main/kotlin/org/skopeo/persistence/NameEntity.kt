// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.persistence

import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of a `user_names` row (#633): the dumb, as-stored data with **no behaviour**. A
 * flat aggregate — no child rows and no derived fields — so the corresponding domain
 * `org.skopeo.model.Name` is a field-for-field copy, except [type] is held as the RAW stored `String`
 * (the domain's `NameType` enum is parsed at the conversion boundary, since `persistence` is a leaf that
 * must not import `model`). The repository maps a DB row to this, then converts it to the domain `Name`
 * at a single boundary (`NameEntity.toDomain`).
 */
data class NameEntity(
    val id: UUID,
    val userId: UUID,
    val type: String,
    val value: String,
    val isActive: Boolean,
    val disabledAt: LocalDateTime?,
)
