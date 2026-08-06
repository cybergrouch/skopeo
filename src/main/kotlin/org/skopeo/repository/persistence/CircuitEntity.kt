// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.util.UUID

/**
 * Raw persistence view of a `circuits` row (#633): the dumb, as-stored data with **no behaviour**.
 * A flat aggregate — no child rows and no derived fields — so the corresponding domain
 * `org.skopeo.domain.model.Circuit` is a field-for-field copy; the split is purely structural (raw persistence
 * type vs the service's domain type). Kept **model-free** (only stdlib types) so `persistence` stays a
 * leaf package — the repository maps a DB row to this, then converts it to the domain `Circuit` at a
 * single boundary (`CircuitEntity.toDomain`).
 */
data class CircuitEntity(
    val id: UUID,
    val name: String,
    val isActive: Boolean,
    val createdBy: UUID?,
)
