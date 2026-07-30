// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.settings

import org.skopeo.contract.OpenPlayPointsConfig
import org.skopeo.contract.TournamentPointsConfig
import org.skopeo.dto.settings.OpenPlayConfigResponse
import org.skopeo.dto.settings.TournamentConfigResponse
import org.skopeo.model.StoredConfig

fun StoredConfig<OpenPlayPointsConfig>.toResponse(): OpenPlayConfigResponse =
    OpenPlayConfigResponse(config = value, updatedAt = updatedAt?.toString(), updatedBy = updatedBy?.toString())

fun StoredConfig<TournamentPointsConfig>.toResponse(): TournamentConfigResponse =
    TournamentConfigResponse(config = value, updatedAt = updatedAt?.toString(), updatedBy = updatedBy?.toString())
