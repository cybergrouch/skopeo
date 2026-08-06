// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.util.UUID

/**
 * Raw persistence graph of an event (#633): the [event] row plus its separately-loaded APPROVED
 * participant ids (from the `event_participants` join table). This is the shape `EventRepository`
 * returns — only the repository can run the extra query, so it bundles the children here and the
 * `mapper.entity` conversion builds the domain `Event` (parsing the raw [EventEntity.type]) with no
 * further DB access. Kept **model-free** (a raw [EventEntity] + plain [UUID]s) so `persistence`
 * stays a leaf.
 */
data class EventAggregateEntity(
    val event: EventEntity,
    val participantIds: List<UUID>,
)
