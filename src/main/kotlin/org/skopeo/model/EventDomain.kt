// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.time.LocalDate
import java.util.UUID

/**
 * An event/meet (issue #138): a host-run gathering with a date range and a roster of participants,
 * that contains matches. [publicCode] is the shareable code for its public page (mirrors matches).
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

/** Everything needed to create an event. */
data class CreateEventCommand(
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val participantIds: List<UUID>,
    val createdBy: UUID,
)

/** A participant resolved to a display name + shareable code, for rendering the roster (#138). */
data class EventParticipantRef(
    val userId: UUID,
    val displayName: String?,
    val publicCode: String?,
)

/** An event with its participants resolved to names/codes — the shape the API returns. */
data class EventView(
    val event: Event,
    val participants: List<EventParticipantRef>,
)
