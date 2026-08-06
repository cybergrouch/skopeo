// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * A stored configuration [value] with its provenance (#552/#553): who last wrote it ([updatedBy]) and
 * when ([updatedAt]), both null while the install is still on the seeded default (no row persisted).
 */
data class StoredConfig<T>(
    val value: T,
    val updatedBy: UUID?,
    val updatedAt: LocalDateTime?,
)
