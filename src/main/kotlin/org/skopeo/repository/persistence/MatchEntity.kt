// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Raw persistence view of a `matches` row (#633): the dumb, as-stored data with **no behaviour and no
 * assembled children**. Contrast the domain `org.skopeo.model.Match`, which carries the assembled
 * `team1`/`team2` sides (with their user ids) and the `sets` list (each with an optional tiebreak) — all
 * loaded from other tables. This entity holds only the raw `matches`-row scalars, including the raw team
 * FK columns ([team1Id]/[team2Id]); the sides and sets are attached, and the enum columns
 * ([matchFormat]/[matchType]/[status]/[placementBracket], held here as raw `String`) parsed, at the single
 * `MatchEntity.toDomain(...)` boundary in the repository. Kept **model-free** so `persistence` stays a leaf.
 */
data class MatchEntity(
    val id: UUID,
    val publicCode: String,
    val matchFormat: String,
    val matchType: String,
    val matchDate: LocalDate,
    val status: String,
    val team1Id: UUID,
    val team2Id: UUID,
    val winnerTeamId: UUID?,
    val venue: String?,
    val tournamentName: String?,
    val isActive: Boolean,
    val completedAt: LocalDateTime?,
    val ratedAt: LocalDateTime?,
    val createdBy: UUID?,
    val recordedBy: UUID?,
    val eventId: UUID?,
    val calcSequence: Int?,
    val team1Handicap: BigDecimal?,
    val team2Handicap: BigDecimal?,
    val isPlacementMatch: Boolean,
    val placementBracket: String?,
)
