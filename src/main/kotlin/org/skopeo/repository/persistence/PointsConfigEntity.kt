// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of a `points_config` row (#552/#553/#633): the stored JSON `value` plus who last
 * wrote it and when. A genuinely raw key-value pair — there is no separate domain type; the service reads
 * this entity and decodes `value` into the points schedule. Kept **model-free** (only stdlib types) so
 * `persistence` stays a leaf package. Relocated here from `PointsConfigRepository` during the #633
 * rollout so all raw persistence types live in one package.
 */
data class PointsConfigEntity(
    val key: String,
    val value: String,
    val updatedBy: UUID?,
    val updatedAt: LocalDateTime,
)
