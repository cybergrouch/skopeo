// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.settings

import org.skopeo.common.contract.FullMatchPointsConfig
import org.skopeo.common.contract.OpenPlayPointsConfig
import org.skopeo.common.contract.TournamentPointsConfig
import org.skopeo.common.dto.settings.FullMatchConfigResponse
import org.skopeo.common.dto.settings.OpenPlayConfigResponse
import org.skopeo.common.dto.settings.TournamentConfigResponse
import org.skopeo.domain.model.StoredConfig

fun StoredConfig<OpenPlayPointsConfig>.toResponse(): OpenPlayConfigResponse =
    OpenPlayConfigResponse(config = value, updatedAt = updatedAt?.toString(), updatedBy = updatedBy?.toString())

fun StoredConfig<TournamentPointsConfig>.toResponse(): TournamentConfigResponse =
    TournamentConfigResponse(config = value, updatedAt = updatedAt?.toString(), updatedBy = updatedBy?.toString())

fun StoredConfig<FullMatchPointsConfig>.toResponse(): FullMatchConfigResponse =
    FullMatchConfigResponse(config = value, updatedAt = updatedAt?.toString(), updatedBy = updatedBy?.toString())
