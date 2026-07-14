// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.time.LocalDate
import java.util.UUID

/**
 * A participant's standing in an event (#201). APPROVED is a full roster member (eligible for
 * fixtures/seeding); PENDING is a self-signup awaiting the host's review; HOLD is a soft deny — the
 * request stays on file (not currently considered) and can later be approved.
 */
enum class EventParticipantStatus { PENDING, APPROVED, HOLD }

/** A participant membership row: the user and their [status] in the event (#201). */
data class EventParticipantEntry(
    val userId: UUID,
    val status: EventParticipantStatus,
)

/**
 * An event/meet (issue #138): a host-run gathering with a date range and a roster of participants,
 * that contains matches. [publicCode] is the shareable code for its public page (mirrors matches).
 * [participantIds] is the **APPROVED** roster only — the players eligible for the event's fixtures and
 * seeding; pending/held requests are tracked separately ([EventParticipantEntry], #201).
 */
data class Event(
    val id: UUID,
    val publicCode: String,
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val participantIds: List<UUID>,
    val isActive: Boolean = true,
    val createdBy: UUID? = null,
)

/**
 * True once the event is over — [asOf] is past its [Event.endDate]. Used to gate host data entry
 * (#310): a HOST may not add participants / create fixtures / record results on an expired event,
 * while an ADMINISTRATOR still may. Entry is allowed through the end date itself.
 */
fun Event.isExpired(asOf: LocalDate): Boolean = asOf.isAfter(endDate)

/** Everything needed to create an event. */
data class CreateEventCommand(
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val participantIds: List<UUID>,
    val createdBy: UUID,
)

/**
 * A participant resolved for rendering the roster (#138): display name + shareable code, plus the
 * disambiguating facets a host needs at a glance — [sex], [age], and the current [rating]. The latter
 * three mirror the player-summary shape (sex/age and the NTRP band, raw DOB withheld).
 */
data class EventParticipantRef(
    val userId: UUID,
    val displayName: String?,
    val publicCode: String?,
    val sex: String? = null,
    val age: Int? = null,
    val rating: UserRating? = null,
    // The participant's standing (#201): APPROVED roster member, PENDING request, or HOLD (soft deny).
    val status: EventParticipantStatus = EventParticipantStatus.APPROVED,
)

/**
 * An event with its participants resolved to names/codes — the shape the API returns. [creator] is
 * the filing host resolved to a display name + public code (#270), or null for legacy events with no
 * recorded creator.
 */
data class EventView(
    val event: Event,
    val participants: List<EventParticipantRef>,
    val creator: EventCreatorRef? = null,
)

/** The host who filed an event (#270), identified the privacy-conscious way — display name + public code. */
data class EventCreatorRef(
    val displayName: String?,
    val publicCode: String?,
)

/** One of a player's own events plus their standing in it (#202) — backs the Profile "Events history". */
data class MyEvent(
    val event: Event,
    val status: EventParticipantStatus,
)
