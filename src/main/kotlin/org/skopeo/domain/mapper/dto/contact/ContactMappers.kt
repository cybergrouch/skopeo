// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.contact

import org.skopeo.domain.model.Contact
import org.skopeo.dto.contact.ContactResponse

fun Contact.toResponse(): ContactResponse =
    ContactResponse(
        id = id.toString(),
        userId = userId.toString(),
        type = type.name,
        value = value,
        source = source.name,
        status = status.name,
        method = method?.name,
        isPrimary = isPrimary,
        isActive = isActive,
        verifiedAt = verifiedAt?.toString(),
        verifiedBy = verifiedBy?.toString(),
        disabledAt = disabledAt?.toString(),
    )
