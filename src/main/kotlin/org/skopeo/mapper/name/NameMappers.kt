// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.name

import org.skopeo.dto.name.NameResponse
import org.skopeo.model.Name

fun Name.toResponse(): NameResponse =
    NameResponse(
        id = id.toString(),
        userId = userId.toString(),
        type = type.name,
        value = value,
        isActive = isActive,
        disabledAt = disabledAt?.toString(),
    )
