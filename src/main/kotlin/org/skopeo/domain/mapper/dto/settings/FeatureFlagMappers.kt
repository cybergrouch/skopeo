// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.settings

import org.skopeo.common.dto.settings.AwardRankingPointsResponse
import org.skopeo.common.dto.settings.CalibrationMatchesResponse
import org.skopeo.common.dto.settings.FacebookLoginResponse
import org.skopeo.common.dto.settings.HideRankingPointsResponse
import org.skopeo.domain.model.AwardRankingPointsValue
import org.skopeo.domain.model.CalibrationMatchesValue
import org.skopeo.domain.model.FacebookLoginValue
import org.skopeo.domain.model.HideRankingPointsValue

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

/** The hide-ranking-points flag for the API (#865). */
fun HideRankingPointsValue.toResponse(): HideRankingPointsResponse =
    HideRankingPointsResponse(
        hidden = hidden,
        updatedAt = updatedAt?.toString(),
        updatedBy = updatedBy?.toString(),
    )

/** [CalibrationMatchesValue] → its wire form (#881). */
fun CalibrationMatchesValue.toResponse(): CalibrationMatchesResponse =
    CalibrationMatchesResponse(
        matches = matches,
        updatedAt = updatedAt?.toString(),
        updatedBy = updatedBy?.toString(),
    )
