// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.time.LocalDate
import java.util.UUID

/**
 * A club (#313): a named organization an event can optionally belong to. Administrators create clubs
 * and assign CLUB_OWNER(s); events may then be grouped by their club in the organizer view.
 */
data class Club(
    val id: UUID,
    val name: String,
    // The shareable code for the club's public-by-code page (#327), mirroring events/matches.
    val publicCode: String,
    val isActive: Boolean = true,
    // Whether tournaments hosted by this club are sanctioned (#525); a tournament inherits this.
    val tournamentsSanctioned: Boolean = false,
    val createdBy: UUID? = null,
    val ownerIds: List<UUID> = emptyList(),
)

/** Everything needed to create a club. */
data class CreateClubCommand(
    val name: String,
    val createdBy: UUID,
)

/** A club owner resolved for presentation — the user's id plus their display name and public code. */
data class ClubOwnerRef(
    val userId: UUID,
    val displayName: String?,
    val publicCode: String,
)

/** A club plus its resolved owners — the presentation shape returned by the service. */
data class ClubView(
    val id: UUID,
    val name: String,
    // The shareable code for the club's public-by-code page (#327).
    val publicCode: String,
    val isActive: Boolean,
    // Whether this club's tournaments are sanctioned (#525).
    val tournamentsSanctioned: Boolean,
    val owners: List<ClubOwnerRef>,
)

/**
 * One of a club's events on its public page (#327), reduced to the non-sensitive fields the page
 * links on: the shareable code, name, and date range. No roster/owner PII is surfaced here.
 *
 * Per-event points are public (#403 Phase E): [eventType] plus both [designatedPoints] (the planned
 * total) and [awardedPoints] (the finalized/awarded total). The UI shows awarded once the event is
 * finalized, else designated. Club utilization (Budgeted/Allocated/Free) is NOT public — it lives on
 * the gated points-summary, never here.
 */
data class ClubPublicEvent(
    val publicCode: String,
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val eventType: EventType,
    val designatedPoints: Int,
    val awardedPoints: Int,
)

/**
 * Read-only public view of a club (#327): its name plus the events it organizes, split into
 * [upcoming] (still running or in the future) and [past] (already ended), each sorted for display.
 * Owners and other sensitive fields are deliberately withheld. [isActive] is false once the club has
 * been soft-deleted — the link stays honored for traceability and the page flags it as deleted.
 */
data class ClubPublicView(
    val publicCode: String,
    val name: String,
    val isActive: Boolean,
    val upcoming: List<ClubPublicEvent>,
    val past: List<ClubPublicEvent>,
)
