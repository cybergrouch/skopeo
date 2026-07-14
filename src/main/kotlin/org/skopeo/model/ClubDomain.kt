// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.util.UUID

/**
 * A club (#313): a named organization an event can optionally belong to. Administrators create clubs
 * and assign CLUB_OWNER(s); events may then be grouped by their club in the organizer view.
 */
data class Club(
    val id: UUID,
    val name: String,
    val isActive: Boolean = true,
    val createdBy: UUID? = null,
    val ownerIds: List<UUID> = emptyList(),
)

/** Everything needed to create a club. */
data class CreateClubCommand(
    val name: String,
    val createdBy: UUID,
)

/** A club owner resolved for presentation — the user's id plus their display name and public code. */
data class ClubOwnerRef(
    val userId: UUID,
    val displayName: String?,
    val publicCode: String,
)

/** A club plus its resolved owners — the presentation shape returned by the service. */
data class ClubView(
    val id: UUID,
    val name: String,
    val isActive: Boolean,
    val owners: List<ClubOwnerRef>,
)
