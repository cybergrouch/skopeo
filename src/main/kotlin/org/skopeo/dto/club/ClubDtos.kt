// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.club

import kotlinx.serialization.Serializable

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

/** Body for `PATCH /api/v1/clubs/{id}/sanction` — set whether the club's tournaments are sanctioned (#525). */
@Serializable
data class SetSanctionRequest(
    val sanctioned: Boolean,
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
    // The shareable code for the club's public-by-code page (#327).
    val publicCode: String,
    val isActive: Boolean,
    // Whether this club's tournaments are sanctioned (#525).
    val tournamentsSanctioned: Boolean = false,
    val owners: List<ClubOwnerDto>,
)

/**
 * One of a club's events on its public page (#327): the shareable code, name, date range, and type.
 */
@Serializable
data class ClubPublicEventDto(
    val publicCode: String,
    val name: String,
    val startDate: String,
    val endDate: String,
    val eventType: String,
)

/**
 * Read-only public summary of a club (#327): its name plus the events it organizes, split into
 * [upcoming] and [past]. No owner/roster PII is exposed. [isActive] is false once the club has been
 * soft-deleted, so the public page can flag it while the link stays honored.
 */
@Serializable
data class ClubPublicResponse(
    val publicCode: String,
    val name: String,
    val isActive: Boolean = true,
    val upcoming: List<ClubPublicEventDto>,
    val past: List<ClubPublicEventDto>,
)
