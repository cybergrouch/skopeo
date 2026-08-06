// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.dto.seeding

import org.skopeo.dto.seeding.PlayerListSummaryResponse
import org.skopeo.dto.seeding.SeedingEntryResponse
import org.skopeo.dto.seeding.SeedingResponse
import org.skopeo.model.PlayerList
import org.skopeo.model.Seeding
import org.skopeo.model.SeedingEntry

fun PlayerList.toSummaryResponse(): PlayerListSummaryResponse =
    PlayerListSummaryResponse(
        id = id.toString(),
        name = name,
        createdAt = createdAt.toString(),
        memberCount = memberUserIds.size,
    )

fun Seeding.toResponse(showRawRating: Boolean = false): SeedingResponse =
    SeedingResponse(
        generatedAt = generatedAt.toString(),
        entries = entries.map { it.toResponse(showRawRating = showRawRating) },
    )

fun SeedingEntry.toResponse(showRawRating: Boolean = false): SeedingEntryResponse =
    SeedingEntryResponse(
        seed = seed,
        position = position,
        userId = userId?.toString(),
        displayName = displayName,
        publicCode = publicCode,
        ntrpBand = ntrpBand,
        // Raw rating value is ADMINISTRATOR-only (#583); non-admin staff see the band + seed order only.
        rating = if (showRawRating) rating else null,
        sex = sex,
        age = age,
        isPlaceholder = placeholder,
        isDeleted = deleted,
    )
