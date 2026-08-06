// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.persistence

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of an `events` row (#633): only the root-row scalar columns, no child rows
 * (the participant roster lives in `event_participants`) and no derived fields (`isFinalized` is a
 * model getter). Model-free leaf — [type] is held raw as [String]. The repository bundles this row
 * with its separately-loaded APPROVED participant ids into an [EventAggregateEntity]; the
 * `mapper.entity` conversion then parses [type] and builds the domain `Event`.
 */
data class EventEntity(
    val id: UUID,
    val publicCode: String,
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val isActive: Boolean,
    val createdBy: UUID?,
    val clubId: UUID?,
    val circuitId: UUID?,
    val calcPriority: Double?,
    // Raw event-type enum name (e.g. OPEN_PLAY | TOURNAMENT); parsed to the domain enum in toDomain.
    val type: String,
    val finalizedAt: LocalDateTime?,
    val finalizedBy: UUID?,
    val awardRankingPoints: Boolean,
)
