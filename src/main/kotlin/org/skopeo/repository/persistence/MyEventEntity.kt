// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

/**
 * Raw persistence graph of one of a player's own events plus their standing in it (#633) — the shape
 * `EventRepository.findForParticipant` returns, mirroring the domain `MyEvent`. Holds the raw
 * [EventAggregateEntity] graph and the participant [status] as its stored `String` value (parsed to
 * the domain enum in `mapper.entity`). Kept **model-free** so `persistence` stays a leaf.
 */
data class MyEventEntity(
    val event: EventAggregateEntity,
    val status: String,
)
