// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.club

import org.skopeo.common.dto.club.ClubOwnerDto
import org.skopeo.common.dto.club.ClubPublicEventDto
import org.skopeo.common.dto.club.ClubPublicResponse
import org.skopeo.common.dto.club.ClubResponse
import org.skopeo.domain.model.ClubPublicEvent
import org.skopeo.domain.model.ClubPublicView
import org.skopeo.domain.model.ClubView

fun ClubView.toResponse(): ClubResponse =
    ClubResponse(
        id = id.toString(),
        name = name,
        publicCode = publicCode,
        isActive = isActive,
        tournamentsSanctioned = tournamentsSanctioned,
        owners =
            owners.map {
                ClubOwnerDto(userId = it.userId.toString(), displayName = it.displayName, publicCode = it.publicCode)
            },
    )

private fun ClubPublicEvent.toDto(): ClubPublicEventDto =
    ClubPublicEventDto(
        publicCode = publicCode,
        name = name,
        startDate = startDate.toString(),
        endDate = endDate.toString(),
        eventType = eventType.name,
    )

fun ClubPublicView.toResponse(): ClubPublicResponse =
    ClubPublicResponse(
        publicCode = publicCode,
        name = name,
        isActive = isActive,
        upcoming = upcoming.map { it.toDto() },
        past = past.map { it.toDto() },
    )
