// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.entity.capability

import org.skopeo.domain.model.CapabilityGrant
import org.skopeo.repository.persistence.CapabilityGrantEntity

/**
 * Entity→domain mapper (#633): builds the domain [CapabilityGrant] from the raw persistence [CapabilityGrantEntity]
 * the repository returns. A flat aggregate, so this is a field-for-field copy. Lives in `mapper.entity` (which may
 * depend on both `persistence` and `model`); the service calls it, since `repository ↛ mapper`.
 */
fun CapabilityGrantEntity.toDomain(): CapabilityGrant =
    CapabilityGrant(
        id = id,
        userId = userId,
        capability = capability,
        isActive = isActive,
        grantedBy = grantedBy,
        grantedAt = grantedAt,
        revokedBy = revokedBy,
        revokedAt = revokedAt,
    )
