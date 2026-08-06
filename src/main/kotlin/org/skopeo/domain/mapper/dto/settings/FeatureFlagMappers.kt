// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.settings

import org.skopeo.common.dto.settings.AwardRankingPointsResponse
import org.skopeo.common.dto.settings.FacebookLoginResponse
import org.skopeo.domain.model.AwardRankingPointsValue
import org.skopeo.domain.model.FacebookLoginValue

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
