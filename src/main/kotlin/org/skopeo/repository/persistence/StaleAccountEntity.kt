// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.time.LocalDateTime
import java.util.UUID

/**
 * An account the stale-account rule matches (#1122): just what the sweep reports and the pending list
 * needs for its countdown. Model-free (stdlib types only) so `persistence` stays a leaf package.
 */
data class StaleAccountEntity(
    val userId: UUID,
    val publicCode: String,
    val createdAt: LocalDateTime,
)
