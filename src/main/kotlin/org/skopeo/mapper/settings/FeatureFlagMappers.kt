// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.settings

import org.skopeo.dto.settings.AwardRankingPointsResponse
import org.skopeo.dto.settings.FacebookLoginResponse
import org.skopeo.model.AwardRankingPointsValue
import org.skopeo.model.FacebookLoginValue

fun FacebookLoginValue.toResponse(): FacebookLoginResponse =
    FacebookLoginResponse(
        enabled = enabled,
        updatedAt = updatedAt?.toString(),
        updatedBy = updatedBy?.toString(),
    )

fun AwardRankingPointsValue.toResponse(): AwardRankingPointsResponse =
    AwardRankingPointsResponse(
        enabled = enabled,
        updatedAt = updatedAt?.toString(),
        updatedBy = updatedBy?.toString(),
    )
