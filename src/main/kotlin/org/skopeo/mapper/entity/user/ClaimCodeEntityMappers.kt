// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.entity.user

import org.skopeo.model.ClaimCode
import org.skopeo.model.ClaimCodeStatus
import org.skopeo.repository.persistence.ClaimCodeEntity

/**
 * Entity→domain mapper (#633): builds the domain [ClaimCode] from the raw persistence [ClaimCodeEntity]
 * the repository returns. Parses the raw [ClaimCodeEntity.status] String into the [ClaimCodeStatus] enum
 * at this boundary. Lives in `mapper.entity` (which may depend on both `persistence` and `model`); the
 * service calls it, since `repository ↛ mapper`.
 */
fun ClaimCodeEntity.toDomain(): ClaimCode =
    ClaimCode(
        id = id,
        placeholderUserId = placeholderUserId,
        codeHash = codeHash,
        expiresAt = expiresAt,
        status = ClaimCodeStatus.valueOf(value = status),
        createdBy = createdBy,
        createdAt = createdAt,
        consumedAt = consumedAt,
        consumedBy = consumedBy,
    )
