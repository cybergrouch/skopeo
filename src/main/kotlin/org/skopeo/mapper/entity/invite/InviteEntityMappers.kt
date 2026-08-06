// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.entity.invite

import org.skopeo.model.Invite
import org.skopeo.model.InviteStatus
import org.skopeo.repository.persistence.InviteEntity

/**
 * Entity→domain mapper (#633): builds the domain [Invite] from the raw persistence [InviteEntity] the
 * repository returns. A flat aggregate, so this is a field-for-field copy save for [status], which is
 * stored raw as a `String` and parsed into the [InviteStatus] enum here at the domain boundary. Lives
 * in `mapper.entity` (which may depend on both `persistence` and `model`); the service calls it, since
 * `repository ↛ mapper`.
 */
fun InviteEntity.toDomain(): Invite =
    Invite(
        id = id,
        email = email,
        status = InviteStatus.valueOf(value = status),
        invitedBy = invitedBy,
        expiresAt = expiresAt,
        acceptedAt = acceptedAt,
        createdAt = createdAt,
    )
