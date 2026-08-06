// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.dto.capability

import org.skopeo.dto.capability.CapabilityResponse
import org.skopeo.model.CapabilityGrant

fun CapabilityGrant.toResponse(): CapabilityResponse =
    CapabilityResponse(
        capability = capability.name,
        isActive = isActive,
        grantedBy = grantedBy?.toString(),
        grantedAt = grantedAt?.toString(),
        revokedBy = revokedBy?.toString(),
        revokedAt = revokedAt?.toString(),
    )
