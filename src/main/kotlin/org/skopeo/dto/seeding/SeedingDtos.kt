// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.seeding

import kotlinx.serialization.Serializable
import org.skopeo.dto.user.UserSummaryResponse

/** Body for creating a named player list (#111). */
@Serializable
data class CreatePlayerListRequest(
    val name: String,
)

/** Body for adding a member to a list. */
@Serializable
data class AddMemberRequest(
    val userId: String,
)

/** A list in the host's index — without the full member roster. */
@Serializable
data class PlayerListSummaryResponse(
    val id: String,
    val name: String,
    val createdAt: String,
    val memberCount: Int,
)

/** A single list with its resolved members. */
@Serializable
data class PlayerListResponse(
    val id: String,
    val name: String,
    val createdAt: String,
    val members: List<UserSummaryResponse>,
)

/**
 * One seeding row (#111). Seeding orders by the exact rating, but the raw [rating] value is surfaced
 * only to an ADMINISTRATOR (#583) — non-admin staff (HOST/CLUB_OWNER) get the [ntrpBand] and the seed
 * order, with [rating] null. The seed ordering itself is unaffected (computed server-side from the
 * exact rating regardless).
 */
@Serializable
data class SeedingEntryResponse(
    val seed: Int? = null,
    val position: Int,
    val userId: String? = null,
    val displayName: String? = null,
    val publicCode: String,
    val ntrpBand: String? = null,
    val rating: String? = null,
    val sex: String? = null,
    val age: Int? = null,
    // True for a login-less, not-yet-claimed placeholder ("dummy") player (#496/#505): the seeding view
    // renders an "Unclaimed" tag beside the name. Real/claimed players leave it false.
    val isPlaceholder: Boolean = false,
    // True for an admin-soft-deleted account (#518): the seeding view renders a dominant "Deleted" chip.
    val isDeleted: Boolean = false,
)

/** A generated seeding: the timestamp plus the rating-sorted rows. */
@Serializable
data class SeedingResponse(
    val generatedAt: String,
    val entries: List<SeedingEntryResponse>,
)
