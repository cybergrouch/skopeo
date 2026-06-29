// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.event

import kotlinx.serialization.Serializable
import org.skopeo.model.EventParticipantRef
import org.skopeo.model.EventView

/** Body for `POST /api/v1/events` — create an event (name, date range, roster). */
@Serializable
data class CreateEventRequest(
    val name: String,
    val startDate: String,
    val endDate: String,
    val participantIds: List<String> = emptyList(),
)

/** Body for `POST /api/v1/events/{id}/participants` — add a participant. */
@Serializable
data class AddParticipantRequest(
    val userId: String,
)

/** A participant on an event, resolved to a display name + shareable code. */
@Serializable
data class EventParticipantResponse(
    val userId: String,
    val displayName: String? = null,
    val publicCode: String? = null,
)

@Serializable
data class EventResponse(
    val id: String,
    val publicCode: String,
    val name: String,
    val startDate: String,
    val endDate: String,
    val isActive: Boolean,
    val participants: List<EventParticipantResponse>,
)

fun EventView.toResponse(): EventResponse =
    EventResponse(
        id = event.id.toString(),
        publicCode = event.publicCode,
        name = event.name,
        startDate = event.startDate.toString(),
        endDate = event.endDate.toString(),
        isActive = event.isActive,
        participants = participants.map { it.toResponse() },
    )

private fun EventParticipantRef.toResponse(): EventParticipantResponse =
    EventParticipantResponse(userId = userId.toString(), displayName = displayName, publicCode = publicCode)
