// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.persistence

import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of an `app_settings` row (#378/#633): the stored key/value plus who last wrote it
 * and when. This is a genuinely raw key-value pair — there is no separate domain type; the service reads
 * this entity and interprets `value` per setting (e.g. coalescing to a feature-flag boolean). Kept
 * **model-free** (only stdlib types) so `persistence` stays a leaf package. Relocated here from
 * `AppSettingsRepository` during the #633 rollout so all raw persistence types live in one package.
 */
data class AppSettingEntity(
    val key: String,
    val value: String,
    val updatedBy: UUID?,
    val updatedAt: LocalDateTime,
)
