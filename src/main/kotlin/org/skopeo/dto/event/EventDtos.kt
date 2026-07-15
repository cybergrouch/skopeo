// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.event

import kotlinx.serialization.Serializable
import org.skopeo.dto.match.MatchPublicResponse
import org.skopeo.dto.user.PublicRatingDto
import org.skopeo.model.EventParticipantRef
import org.skopeo.model.EventView
import org.skopeo.model.MyEvent

/** Body for `POST /api/v1/events` — create an event (name, date range, roster). */
@Serializable
data class CreateEventRequest(
    val name: String,
    val startDate: String,
    val endDate: String,
    val participantIds: List<String> = emptyList(),
    // Optional club (#313) to assign the event to; omit for a clubless ("Open") event.
    val clubId: String? = null,
)

/**
 * Body for `PATCH /api/v1/events/{id}` — a partial update of an event (#269). Only [name] is
 * editable today; the shape is a partial update so date/other-field edits can be added later
 * without a new route. Fields left null are unchanged.
 */
@Serializable
data class UpdateEventRequest(
    val name: String? = null,
)

/** Body for `PUT /api/v1/events/{id}/club` — set the event's club, or clear it when null (#319). */
@Serializable
data class SetEventClubRequest(
    val clubId: String? = null,
)

/** Body for `PUT /api/v1/events/{id}/calculation-priority` — set the calculation processing order (#335). */
@Serializable
data class SetCalcPriorityRequest(
    val priority: Double,
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

/**
 * A player's own event (#202) for the Profile "Events history": the event's details plus the
 * caller's standing ([status]: APPROVED | PENDING | HOLD). The client splits upcoming vs past by date.
 */
@Serializable
data class MyEventResponse(
    val publicCode: String,
    val name: String,
    val startDate: String,
    val endDate: String,
    val status: String,
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
    // The filing host (#270): display name + public code, or null for legacy events with no creator.
    val creatorDisplayName: String? = null,
    val creatorPublicCode: String? = null,
    // The event's club (#313): id + name, or null for a clubless event.
    val clubId: String? = null,
    val clubName: String? = null,
    // Admin override for calculation processing order (#335); null = order by end date.
    val calcPriority: Double? = null,
)

fun MyEvent.toResponse(): MyEventResponse =
    MyEventResponse(
        publicCode = event.publicCode,
        name = event.name,
        startDate = event.startDate.toString(),
        endDate = event.endDate.toString(),
        status = status.name,
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
        creatorDisplayName = creator?.displayName,
        creatorPublicCode = creator?.publicCode,
        clubId = club?.id?.toString(),
        clubName = club?.name,
        calcPriority = event.calcPriority,
    )

internal fun EventParticipantRef.toResponse(): EventParticipantResponse =
    EventParticipantResponse(
        userId = userId.toString(),
        displayName = displayName,
        publicCode = publicCode,
        sex = sex,
        age = age,
        rating =
            rating?.let {
                PublicRatingDto(
                    value = it.currentRating.toPlainString(),
                    level = it.currentLevel,
                    confidence = it.confidence.toPlainString(),
                )
            },
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
    // The organizing club's name (#313), read-only; null for a clubless ("Open") event.
    val clubName: String? = null,
    // False once the event has been soft-deleted (#325): its link stays honored for traceability, and
    // the public page flags it as deleted.
    val isActive: Boolean = true,
    val participants: List<EventParticipantResponse>,
    val matches: List<MatchPublicResponse>,
    // The viewer's own standing on this event (#201): "APPROVED" | "PENDING" | "HOLD", or null if
    // they haven't signed up (so the page can offer "Request to join").
    val viewerStatus: String? = null,
)
