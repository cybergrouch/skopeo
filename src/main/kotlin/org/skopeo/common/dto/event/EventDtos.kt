// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.dto.event

import kotlinx.serialization.Serializable
import org.skopeo.common.dto.match.MatchPublicResponse
import org.skopeo.common.dto.user.PublicRatingDto

/** Body for `POST /api/v1/events` — create an event (name, date range, roster). */
@Serializable
data class CreateEventRequest(
    val name: String,
    val startDate: String,
    val endDate: String,
    // The event's organizing format (#720): "SINGLES" | "DOUBLES" | "MIXED_DOUBLES". REQUIRED at create.
    val format: String,
    val participantIds: List<String> = emptyList(),
    // Optional club (#313) to assign the event to; omit for a clubless ("Open") event.
    val clubId: String? = null,
    // The circuit a TOURNAMENT event belongs to (#525); required for tournaments, ignored otherwise.
    val circuitId: String? = null,
    // The event's class (#403): OPEN_PLAY | TOURNAMENT; omit for the OPEN_PLAY default.
    val type: String? = null,
    // Whether finalizing this event awards ranking points per the global schedules (#559). Omit for the
    // default (true — "Award Ranking Points" is on unless the organizer unchecks it).
    val awardRankingPoints: Boolean? = null,
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
    // The event's organizing format (#720): "SINGLES" | "DOUBLES" | "MIXED_DOUBLES".
    val format: String,
    // The event's class (#403): "OPEN_PLAY" | "TOURNAMENT".
    val type: String,
    // When the event was finalized (#403), ISO-8601; null while open.
    val finalizedAt: String? = null,
    // True once the event has been finalized (#403) — closed to changes; its matches queue for rating.
    val isFinalized: Boolean = false,
    // Number of recorded results (COMPLETED with a decided winner) in this event (#483); the client's
    // "has results" signal for the Unfinalized bucket. Zero when no result has been recorded yet.
    val completedMatchCount: Int = 0,
    // True once EVERY recorded result in this event has been rated (#772) — the event list's "Rated"
    // badge. Deliberately binary: a rating run completes in seconds, so a partly-rated event is
    // transient and reads as not-yet-rated. False for an event with no recorded results at all — "all
    // results are rated" is vacuously true of none, which is not what a reader would take it to mean.
    val isRated: Boolean = false,
    // Whether finalizing this event awards ranking points per the global schedules (#559). Default true.
    val awardRankingPoints: Boolean = true,
    // Finalize outcome only (#752): true when this finalize awarded nothing because the global
    // "Award ranking points" flag (#641) is off, even though the event's own flag is set. Always false
    // on the other event reads — it describes what a finalize just did, not durable event state.
    val awardingSuppressedByGlobalFlag: Boolean = false,
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
    // The event's organizing format (#720): "SINGLES" | "DOUBLES" | "MIXED_DOUBLES" (#741).
    val format: String = "SINGLES",
    // The event's class (#403): "OPEN_PLAY" | "TOURNAMENT" (#741).
    val type: String = "OPEN_PLAY",
    // True once the event has been finalized (#403) — closed to changes (#741). The page renders the
    // Finalized badge from this, and withholds "Request to join": a finalized event takes no joiners.
    val isFinalized: Boolean = false,
    // Whether finalizing this event awards ranking points per the global schedules (#559/#741).
    val awardRankingPoints: Boolean = true,
)
