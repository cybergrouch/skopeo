// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.settings

import kotlinx.serialization.Serializable
import org.skopeo.common.contract.OpenPlayPointsConfig
import org.skopeo.common.contract.TournamentPointsConfig

/**
 * The open-play points schedule (#553) with provenance. [config] is the editable margin-bracket table
 * + validity; [updatedAt]/[updatedBy] are null while the install still uses the seeded default.
 */
@Serializable
data class OpenPlayConfigResponse(
    val config: OpenPlayPointsConfig,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
)

/**
 * The tournament placement points schedule (#552) with provenance. [config] is the editable
 * sanctioned/unsanctioned table + validity; provenance is null while the seeded default is in effect.
 */
@Serializable
data class TournamentConfigResponse(
    val config: TournamentPointsConfig,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
)
