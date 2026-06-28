// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.seeding

import kotlinx.serialization.Serializable
import org.skopeo.dto.user.UserSummaryResponse
import org.skopeo.model.PlayerList
import org.skopeo.model.Seeding
import org.skopeo.model.SeedingEntry

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
 * One seeding row (#111). [rating] is the exact rating — this is a staff-only tool
 * (HOST/CLUB_OWNER/ADMINISTRATOR), so the actual value is intentionally surfaced here.
 */
@Serializable
data class SeedingEntryResponse(
    val seed: Int? = null,
    val position: Int,
    val userId: String? = null,
    val displayName: String? = null,
    val publicCode: String,
    val ntrpBand: String? = null,
    val rating: String,
    val sex: String? = null,
    val age: Int? = null,
)

/** A generated seeding: the timestamp plus the rating-sorted rows. */
@Serializable
data class SeedingResponse(
    val generatedAt: String,
    val entries: List<SeedingEntryResponse>,
)

fun PlayerList.toSummaryResponse(): PlayerListSummaryResponse =
    PlayerListSummaryResponse(
        id = id.toString(),
        name = name,
        createdAt = createdAt.toString(),
        memberCount = memberUserIds.size,
    )

fun Seeding.toResponse(): SeedingResponse =
    SeedingResponse(
        generatedAt = generatedAt.toString(),
        entries = entries.map { it.toResponse() },
    )

fun SeedingEntry.toResponse(): SeedingEntryResponse =
    SeedingEntryResponse(
        seed = seed,
        position = position,
        userId = userId?.toString(),
        displayName = displayName,
        publicCode = publicCode,
        ntrpBand = ntrpBand,
        rating = rating,
        sex = sex,
        age = age,
    )
