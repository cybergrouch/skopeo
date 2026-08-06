// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.capability

import org.skopeo.common.dto.capability.CapabilityResponse
import org.skopeo.domain.model.CapabilityGrant

fun CapabilityGrant.toResponse(): CapabilityResponse =
    CapabilityResponse(
        capability = capability.name,
        isActive = isActive,
        grantedBy = grantedBy?.toString(),
        grantedAt = grantedAt?.toString(),
        revokedBy = revokedBy?.toString(),
        revokedAt = revokedAt?.toString(),
    )
