// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The class of an event (#403), distinct from the match-level [MatchType] rating factors (#108):
 * OPEN_PLAY (casual) or TOURNAMENT (1–2 day). It later drives the per-type points budget (Phase B);
 * Phase A only records it. The former LEAGUE type was removed (#669) so event type aligns 1:1 with
 * [MatchType]; existing LEAGUE rows reclassify to OPEN_PLAY.
 */
enum class EventType { OPEN_PLAY, TOURNAMENT }

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
    // The club this event is filed under (#313). Non-null since #794 — every organizer surface is
    // club-scoped, and V44 enforces it in the schema, so no call site needs to re-check.
    val clubId: UUID,
    // The circuit a TOURNAMENT event belongs to (#525); required for tournaments, null otherwise.
    val circuitId: UUID? = null,
    // Admin override for calculation processing order (#335); null = order by end date.
    val calcPriority: Double? = null,
    // The event's organizing format (#720): SINGLES | DOUBLES | MIXED_DOUBLES. NOT NULL in the DB; it
    // sets durable team size and the default fixture format (overridable). Distinct from [type] (#403).
    val format: TeamType = TeamType.SINGLES,
    // The event's class (#403): OPEN_PLAY | TOURNAMENT.
    val type: EventType = EventType.OPEN_PLAY,
    // When the event was finalized (#403); null while open. Finalize is terminal and queues rating.
    val finalizedAt: LocalDateTime? = null,
    // The user who finalized the event (#403); null while open.
    val finalizedBy: UUID? = null,
    // Whether finalizing this event awards ranking points per the global schedules (#559). Default true.
    val awardRankingPoints: Boolean = true,
) {
    /** True once the event has been finalized (#403) — closed to changes; its matches queue for rating. */
    val isFinalized: Boolean get() = finalizedAt != null
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
    val clubId: UUID,
    // The circuit a TOURNAMENT event belongs to (#525); required for tournaments.
    val circuitId: UUID? = null,
    // The event's organizing format (#720): SINGLES | DOUBLES | MIXED_DOUBLES. Required at create; the
    // service always supplies it (defaulted here only to keep existing call sites compiling).
    val format: TeamType = TeamType.SINGLES,
    // The event's class (#403); defaults to OPEN_PLAY for backward compatibility.
    val type: EventType = EventType.OPEN_PLAY,
    // Whether finalizing this event awards ranking points per the global schedules (#559). Default true.
    val awardRankingPoints: Boolean = true,
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

/**
 * The three groupings an event falls into (#483), as evaluated by the SERVER for the paginated club-page
 * listing (#786).
 *
 * Finalized wins over everything: a finalized event is always [FINALIZED], even with a future end date or
 * no results. Otherwise [UNFINALIZED] = the event ended OR has recorded results (activity started, not
 * concluded), and [UPCOMING] = still to come and untouched.
 *
 * These are the same rules the web's `eventBuckets.ts` applies for the Event Organizer, which loads every
 * event anyway to group by club. Two implementations is a known cost of paginating per bucket in SQL
 * (#786); they must be kept in agreement, and the bucket tests mirror the client's cases to pin that.
 * Migrating the organizer onto this endpoint would collapse them back to one.
 */
enum class EventBucket {
    UPCOMING,
    UNFINALIZED,
    FINALIZED,
}

/**
 * The club an event belongs to (#313), resolved for grouping and display. [publicCode] is the club's
 * shareable code (#327) so a club reference beside an event can link straight to that club's public page
 * (#780) without a second lookup — the events list is read by viewers who cannot list clubs.
 */
data class EventClubRef(
    val id: UUID,
    val name: String,
    val publicCode: String,
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
