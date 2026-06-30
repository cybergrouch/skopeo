// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.event

import kotlinx.serialization.Serializable
import org.skopeo.dto.match.MatchPublicResponse
import org.skopeo.dto.user.PublicRatingDto
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

/** Body for `POST /api/v1/events/{id}/participants/{userId}/decision` — APPROVED or HOLD (#201). */
@Serializable
data class DecideParticipantRequest(
    val status: String,
)

/**
 * A participant on an event, resolved to a display name + shareable code, plus the disambiguating
 * facets shown in the roster: [sex], [age], and the current NTRP [rating] band.
 */
@Serializable
data class EventParticipantResponse(
    val userId: String,
    val displayName: String? = null,
    val publicCode: String? = null,
    val sex: String? = null,
    val age: Int? = null,
    val rating: PublicRatingDto? = null,
    // The participant's standing (#201): "APPROVED" | "PENDING" | "HOLD". Null on the public roster
    // (which lists approved members only).
    val status: String? = null,
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

internal fun EventParticipantRef.toResponse(): EventParticipantResponse =
    EventParticipantResponse(
        userId = userId.toString(),
        displayName = displayName,
        publicCode = publicCode,
        sex = sex,
        age = age,
        rating = rating?.let { PublicRatingDto(value = it.currentRating.toPlainString(), level = it.currentLevel) },
        status = status.name,
    )

/**
 * Read-only public summary of an event (#138): its details, participant roster, and the matches it
 * contains (each a [MatchPublicResponse] so the page can link to their public match pages).
 */
@Serializable
data class EventPublicResponse(
    val publicCode: String,
    val name: String,
    val startDate: String,
    val endDate: String,
    val participants: List<EventParticipantResponse>,
    val matches: List<MatchPublicResponse>,
    // The viewer's own standing on this event (#201): "APPROVED" | "PENDING" | "HOLD", or null if
    // they haven't signed up (so the page can offer "Request to join").
    val viewerStatus: String? = null,
)
