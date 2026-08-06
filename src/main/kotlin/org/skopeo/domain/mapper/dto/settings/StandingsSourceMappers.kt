// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.settings

import org.skopeo.domain.model.StandingsSourceValue
import org.skopeo.dto.settings.StandingsSourceResponse

fun StandingsSourceValue.toResponse(): StandingsSourceResponse =
    StandingsSourceResponse(
        source = source.name,
        updatedAt = updatedAt?.toString(),
        updatedBy = updatedBy?.toString(),
    )
