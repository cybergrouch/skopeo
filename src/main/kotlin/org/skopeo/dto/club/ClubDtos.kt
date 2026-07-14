// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.club

import kotlinx.serialization.Serializable
import org.skopeo.model.ClubView

/** Body for `POST /api/v1/clubs` — an administrator creates a club. */
@Serializable
data class CreateClubRequest(
    val name: String,
)

/** Body for `PATCH /api/v1/clubs/{id}` — rename a club (#325). */
@Serializable
data class UpdateClubRequest(
    val name: String,
)

/** Body for `POST /api/v1/clubs/{id}/owners` — assign an existing user as an owner of the club. */
@Serializable
data class AssignOwnerRequest(
    val userId: String,
)

@Serializable
data class ClubOwnerDto(
    val userId: String,
    val displayName: String? = null,
    val publicCode: String,
)

@Serializable
data class ClubResponse(
    val id: String,
    val name: String,
    val isActive: Boolean,
    val owners: List<ClubOwnerDto>,
)

fun ClubView.toResponse(): ClubResponse =
    ClubResponse(
        id = id.toString(),
        name = name,
        isActive = isActive,
        owners =
            owners.map {
                ClubOwnerDto(userId = it.userId.toString(), displayName = it.displayName, publicCode = it.publicCode)
            },
    )
