// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.entity.contact

import org.skopeo.model.Contact
import org.skopeo.model.ContactSource
import org.skopeo.model.ContactType
import org.skopeo.model.VerificationMethod
import org.skopeo.model.VerificationStatus
import org.skopeo.persistence.ContactEntity

/**
 * Entity→domain mapper (#633): builds the domain [Contact] from the raw [ContactEntity] the repository
 * returns. This is the single boundary where the stored enum strings are parsed into their
 * [ContactType]/[ContactSource]/[VerificationStatus]/[VerificationMethod] values. Lives in `mapper.entity`
 * (which may depend on both `persistence` and `model`); the service calls it, since `repository ↛ mapper`.
 */
fun ContactEntity.toDomain(): Contact =
    Contact(
        id = id,
        userId = userId,
        type = ContactType.valueOf(value = type),
        value = value,
        source = ContactSource.valueOf(value = source),
        status = VerificationStatus.valueOf(value = status),
        method = method?.let(block = VerificationMethod::valueOf),
        isPrimary = isPrimary,
        isActive = isActive,
        verifiedAt = verifiedAt,
        verifiedBy = verifiedBy,
        disabledAt = disabledAt,
    )
