// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.entity.club

import org.skopeo.model.Club
import org.skopeo.repository.persistence.ClubAggregateEntity

/**
 * Entity→domain mapper (#633): builds the domain [Club] from the raw [ClubAggregateEntity] graph the
 * repository returns (the `clubs` row plus its loaded owner ids). Lives in `mapper.entity` (which may
 * depend on both `persistence` and `model`); the service calls it, since `repository ↛ mapper`.
 */
fun ClubAggregateEntity.toDomain(): Club =
    Club(
        id = club.id,
        name = club.name,
        publicCode = club.publicCode,
        isActive = club.isActive,
        tournamentsSanctioned = club.tournamentsSanctioned,
        createdBy = club.createdBy,
        ownerIds = ownerIds,
    )
