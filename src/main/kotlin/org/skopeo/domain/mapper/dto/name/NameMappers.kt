// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.name

import org.skopeo.domain.model.Name
import org.skopeo.dto.name.NameResponse

fun Name.toResponse(): NameResponse =
    NameResponse(
        id = id.toString(),
        userId = userId.toString(),
        type = type.name,
        value = value,
        isActive = isActive,
        disabledAt = disabledAt?.toString(),
    )
