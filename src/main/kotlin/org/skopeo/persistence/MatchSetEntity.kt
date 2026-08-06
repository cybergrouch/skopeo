// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.persistence

import java.util.UUID

/**
 * Raw persistence view of a completed set's score (#633): mirrors the domain `MatchSetResult` field for
 * field, loaded from the `match_sets` row plus its optional `match_set_tiebreaks` sub-row. The dumb,
 * as-stored data with no behaviour; it is assembled into the domain `org.skopeo.model.MatchSetResult` at
 * the `MatchAggregateEntity` conversion boundary in `mapper.entity`. Kept **model-free** so `persistence`
 * stays a leaf.
 */
data class MatchSetEntity(
    val setNumber: Int,
    val team1Games: Int,
    val team2Games: Int,
    val winnerTeamId: UUID,
    val tiebreakTeam1Points: Int?,
    val tiebreakTeam2Points: Int?,
)
