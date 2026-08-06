// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.entity.name

import org.skopeo.model.Name
import org.skopeo.model.NameType
import org.skopeo.repository.persistence.NameEntity

/**
 * Entity→domain mapper (#633): builds the domain [Name] from the raw [NameEntity] the repository returns.
 * This is the single boundary where the stored `nameType` string is parsed into the [NameType] enum.
 * Lives in `mapper.entity` (which may depend on both `persistence` and `model`); the service calls it,
 * since `repository ↛ mapper`.
 */
fun NameEntity.toDomain(): Name =
    Name(
        id = id,
        userId = userId,
        type = NameType.valueOf(value = type),
        value = value,
        isActive = isActive,
        disabledAt = disabledAt,
    )
