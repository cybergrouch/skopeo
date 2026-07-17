// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.settings

import kotlinx.serialization.Serializable
import org.skopeo.model.StandingsSourceValue

/**
 * The standings serving source (#146): the [source] name (RATING/POINTS) plus provenance ([updatedAt] as
 * an ISO string, [updatedBy] as a UUID string) — both null when the source has never been explicitly set.
 */
@Serializable
data class StandingsSourceResponse(
    val source: String,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
)

/** Body for `PUT /api/v1/settings/standings-source` — set the standings serving source (RATING/POINTS). */
@Serializable
data class SetStandingsSourceRequest(
    val source: String,
)

fun StandingsSourceValue.toResponse(): StandingsSourceResponse =
    StandingsSourceResponse(
        source = source.name,
        updatedAt = updatedAt?.toString(),
        updatedBy = updatedBy?.toString(),
    )
