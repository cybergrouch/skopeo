// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.mapper.dto.event

import org.skopeo.dto.event.EventParticipantResponse
import org.skopeo.dto.event.EventResponse
import org.skopeo.dto.event.MyEventResponse
import org.skopeo.dto.user.PublicRatingDto
import org.skopeo.model.EventParticipantRef
import org.skopeo.model.EventView
import org.skopeo.model.MyEvent

fun MyEvent.toResponse(completedMatchCount: Int = 0): MyEventResponse =
    MyEventResponse(
        publicCode = event.publicCode,
        name = event.name,
        startDate = event.startDate.toString(),
        endDate = event.endDate.toString(),
        status = status.name,
        isFinalized = event.isFinalized,
        completedMatchCount = completedMatchCount,
    )

fun EventView.toResponse(
    completedMatchCount: Int = 0,
    // Raw NTRP values on the roster are ADMINISTRATOR-only (#583); default false = band only.
    showRawRating: Boolean = false,
): EventResponse =
    EventResponse(
        id = event.id.toString(),
        publicCode = event.publicCode,
        name = event.name,
        startDate = event.startDate.toString(),
        endDate = event.endDate.toString(),
        isActive = event.isActive,
        participants = participants.map { it.toResponse(showRawRating = showRawRating) },
        creatorDisplayName = creator?.displayName,
        creatorPublicCode = creator?.publicCode,
        clubId = club?.id?.toString(),
        clubName = club?.name,
        circuitId = event.circuitId?.toString(),
        calcPriority = event.calcPriority,
        type = event.type.name,
        finalizedAt = event.finalizedAt?.toString(),
        isFinalized = event.isFinalized,
        completedMatchCount = completedMatchCount,
        awardRankingPoints = event.awardRankingPoints,
    )

internal fun EventParticipantRef.toResponse(showRawRating: Boolean = false): EventParticipantResponse =
    EventParticipantResponse(
        userId = userId.toString(),
        displayName = displayName,
        publicCode = publicCode,
        sex = sex,
        age = age,
        rating =
            rating?.let {
                PublicRatingDto(
                    value = if (showRawRating) it.currentRating.toPlainString() else null,
                    level = it.currentLevel,
                    confidence = it.confidence.toPlainString(),
                )
            },
        status = status.name,
        isPlaceholder = placeholder,
        isDeleted = deleted,
    )
