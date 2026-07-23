// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The class of an event (#403), distinct from the match-level [MatchType] rating factors (#108):
 * OPEN_PLAY (casual), LEAGUE (multi-day / season-long, team format), or TOURNAMENT (1–2 day). It
 * later drives the per-type points budget (Phase B); Phase A only records it.
 */
enum class EventType { OPEN_PLAY, LEAGUE, TOURNAMENT }

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
    // The club this event belongs to (#313), or null for a clubless ("Open") event.
    val clubId: UUID? = null,
    // Admin override for calculation processing order (#335); null = order by end date.
    val calcPriority: Double? = null,
    // The event's class (#403): OPEN_PLAY | LEAGUE | TOURNAMENT.
    val type: EventType = EventType.OPEN_PLAY,
    // When the event was finalized (#403); null while open. Finalize is terminal and queues rating.
    val finalizedAt: LocalDateTime? = null,
    // The user who finalized the event (#403); null while open.
    val finalizedBy: UUID? = null,
    // Points config (#403 Phase C): the per-match reward window a fixture may designate within, and the
    // validity window an awarded point stays valid for. Set for a club event of any type (OPEN_PLAY
    // unified), null for a clubless event.
    val minPointsPerMatch: Int? = null,
    val maxPointsPerMatch: Int? = null,
    val pointValidityStart: LocalDate? = null,
    val pointValidityEnd: LocalDate? = null,
) {
    /** True once the event has been finalized (#403) — closed to changes; its matches queue for rating. */
    val isFinalized: Boolean get() = finalizedAt != null
}

/**
 * The event's per-match reward window (#403 Phase C) as a (min, max) pair, or null when the event
 * carries no points config. Config is written atomically (all four fields or none), so the window is
 * present exactly when [Event.minPointsPerMatch] is; [Event.maxPointsPerMatch] then travels with it.
 */
fun Event.pointsWindow(): Pair<Int, Int>? {
    val min = minPointsPerMatch ?: return null
    return min to (maxPointsPerMatch ?: min)
}

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
    val clubId: UUID? = null,
    // The event's class (#403); defaults to OPEN_PLAY for backward compatibility.
    val type: EventType = EventType.OPEN_PLAY,
    // Points config (#403 Phase C): set for a club event of any type (OPEN_PLAY unified), null when clubless.
    val minPointsPerMatch: Int? = null,
    val maxPointsPerMatch: Int? = null,
    val pointValidityStart: LocalDate? = null,
    val pointValidityEnd: LocalDate? = null,
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
    // True for a login-less, not-yet-claimed placeholder ("dummy") player (#496/#505): the roster
    // renders an "Unclaimed" tag beside the name. Real/claimed participants leave it false.
    val placeholder: Boolean = false,
    // True for an admin-soft-deleted account (#518): the roster renders a dominant "Deleted" chip.
    val deleted: Boolean = false,
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
    // The event's club (#313), resolved to id + name for grouping/display; null for a clubless event.
    val club: EventClubRef? = null,
)

/** The club an event belongs to (#313), resolved to its id + name for grouping and display. */
data class EventClubRef(
    val id: UUID,
    val name: String,
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
