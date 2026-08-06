// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.entity.event

import org.skopeo.domain.model.Event
import org.skopeo.domain.model.EventParticipantStatus
import org.skopeo.domain.model.EventType
import org.skopeo.domain.model.MyEvent
import org.skopeo.repository.persistence.EventAggregateEntity
import org.skopeo.repository.persistence.MyEventEntity

/**
 * Entity→domain mapper (#633): builds the domain [Event] from the raw [EventAggregateEntity] graph the
 * repository returns (the `events` row plus its loaded APPROVED participant ids). The raw
 * [org.skopeo.repository.persistence.EventEntity.type] `String` is parsed into the domain [EventType] enum here —
 * the single boundary at which the row becomes a domain object. Lives in `mapper.entity` (which may
 * depend on both `persistence` and `model`); the service calls it, since `repository ↛ mapper`.
 */
fun EventAggregateEntity.toDomain(): Event =
    Event(
        id = event.id,
        publicCode = event.publicCode,
        name = event.name,
        startDate = event.startDate,
        endDate = event.endDate,
        participantIds = participantIds,
        isActive = event.isActive,
        createdBy = event.createdBy,
        clubId = event.clubId,
        circuitId = event.circuitId,
        calcPriority = event.calcPriority,
        type = EventType.valueOf(value = event.type),
        finalizedAt = event.finalizedAt,
        finalizedBy = event.finalizedBy,
        awardRankingPoints = event.awardRankingPoints,
    )

/**
 * Entity→domain mapper (#633): builds the domain [MyEvent] from the raw [MyEventEntity] graph —
 * the event graph via [EventAggregateEntity.toDomain] plus the participant [MyEventEntity.status]
 * parsed into its [EventParticipantStatus] enum.
 */
fun MyEventEntity.toDomain(): MyEvent =
    MyEvent(
        event = event.toDomain(),
        status = EventParticipantStatus.valueOf(value = status),
    )
