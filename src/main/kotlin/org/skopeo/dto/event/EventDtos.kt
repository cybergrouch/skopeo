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
    // The circuit a TOURNAMENT event belongs to (#525); required for tournaments, ignored otherwise.
    val circuitId: String? = null,
    // The event's class (#403): OPEN_PLAY | LEAGUE | TOURNAMENT; omit for the OPEN_PLAY default.
    val type: String? = null,
    // Points config (#403 Phase C): required for a club event of any type (OPEN_PLAY unified), optional
    // when clubless. The validity dates are ISO-8601 (yyyy-MM-dd).
    val minPointsPerMatch: Int? = null,
    val maxPointsPerMatch: Int? = null,
    val pointValidityStart: String? = null,
    val pointValidityEnd: String? = null,
)

/**
 * Body for `PUT /api/v1/events/{id}/points-config` — set (or clear) an event's per-match reward
 * window and point validity window (#466 opt-in "award points" checkbox). All four fields present →
 * the event awards points; all four omitted/null → the config is cleared (the event awards no points,
 * cascading to its fixtures). A partial body is rejected. Validity dates are ISO-8601 (yyyy-MM-dd).
 */
@Serializable
data class SetPointsConfigRequest(
    val minPointsPerMatch: Int? = null,
    val maxPointsPerMatch: Int? = null,
    val pointValidityStart: String? = null,
    val pointValidityEnd: String? = null,
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
    // True for a login-less, not-yet-claimed placeholder ("dummy") player (#496/#505): the roster
    // renders an "Unclaimed" tag beside the name. Real/claimed participants leave it false.
    val isPlaceholder: Boolean = false,
    // True for an admin-soft-deleted account (#518): the roster renders a dominant "Deleted" chip.
    val isDeleted: Boolean = false,
)

/**
 * A player's own event (#202) for the Profile "Events history": the event's details plus the
 * caller's standing ([status]: APPROVED | PENDING | HOLD). The client buckets the event into
 * Finalized / Unfinalized / Upcoming (#483) using [isFinalized], the end date, and
 * [completedMatchCount] (its "has results" signal).
 */
@Serializable
data class MyEventResponse(
    val publicCode: String,
    val name: String,
    val startDate: String,
    val endDate: String,
    val status: String,
    // True once the event has been finalized (#403/#483) — always buckets to Finalized on the client.
    val isFinalized: Boolean = false,
    // Number of recorded results (COMPLETED with a decided winner) in this event (#483); the client's
    // "has results" signal for the Unfinalized bucket. Zero when no result has been recorded yet.
    val completedMatchCount: Int = 0,
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
    // The circuit a TOURNAMENT event belongs to (#525); null for non-tournament events.
    val circuitId: String? = null,
    // Admin override for calculation processing order (#335); null = order by end date.
    val calcPriority: Double? = null,
    // The event's class (#403): "OPEN_PLAY" | "LEAGUE" | "TOURNAMENT".
    val type: String,
    // When the event was finalized (#403), ISO-8601; null while open.
    val finalizedAt: String? = null,
    // True once the event has been finalized (#403) — closed to changes; its matches queue for rating.
    val isFinalized: Boolean = false,
    // Number of recorded results (COMPLETED with a decided winner) in this event (#483); the client's
    // "has results" signal for the Unfinalized bucket. Zero when no result has been recorded yet.
    val completedMatchCount: Int = 0,
    // Points config (#403 Phase C): the per-match reward window and validity dates; null for OPEN_PLAY.
    val minPointsPerMatch: Int? = null,
    val maxPointsPerMatch: Int? = null,
    val pointValidityStart: String? = null,
    val pointValidityEnd: String? = null,
)

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

fun EventView.toResponse(completedMatchCount: Int = 0): EventResponse =
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
        circuitId = event.circuitId?.toString(),
        calcPriority = event.calcPriority,
        type = event.type.name,
        finalizedAt = event.finalizedAt?.toString(),
        isFinalized = event.isFinalized,
        completedMatchCount = completedMatchCount,
        minPointsPerMatch = event.minPointsPerMatch,
        maxPointsPerMatch = event.maxPointsPerMatch,
        pointValidityStart = event.pointValidityStart?.toString(),
        pointValidityEnd = event.pointValidityEnd?.toString(),
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
        isPlaceholder = placeholder,
        isDeleted = deleted,
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
