// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.dto.capability

import kotlinx.serialization.Serializable

/** Body for `POST /api/v1/users/{userId}/capabilities` — grant a role. */
@Serializable
data class CapabilityGrantRequest(
    val capability: String,
)

@Serializable
data class CapabilityResponse(
    val capability: String,
    val isActive: Boolean,
    val grantedBy: String? = null,
    val grantedAt: String? = null,
    val revokedBy: String? = null,
    val revokedAt: String? = null,
)
