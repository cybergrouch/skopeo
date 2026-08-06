// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.entity.user

import org.skopeo.model.DuplicateCandidate
import org.skopeo.model.DuplicateCandidateStatus
import org.skopeo.model.DuplicateSignal
import org.skopeo.persistence.DuplicateCandidateEntity

/**
 * Entity→domain mapper (#633): builds the domain [DuplicateCandidate] from the raw persistence
 * [DuplicateCandidateEntity] the repository returns. A flat aggregate, so this is a field-for-field copy
 * except the two enums ([DuplicateSignal], [DuplicateCandidateStatus]) are parsed from their stored
 * `String`. Lives in `mapper.entity` (which may depend on both `persistence` and `model`); the service
 * calls it, since `repository ↛ mapper`.
 */
fun DuplicateCandidateEntity.toDomain(): DuplicateCandidate =
    DuplicateCandidate(
        id = id,
        userAId = userAId,
        userBId = userBId,
        signal = DuplicateSignal.valueOf(value = signal),
        detail = detail,
        status = DuplicateCandidateStatus.valueOf(value = status),
        flaggedBy = flaggedBy,
        flaggedAt = flaggedAt,
        resolvedBy = resolvedBy,
        resolvedAt = resolvedAt,
    )
