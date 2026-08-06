// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository.persistence

/**
 * Raw persistence graph of a match (#633): the [match] row plus its separately-loaded children — the two
 * sides ([team1]/[team2], each with its ordered user ids from `team_users`) and the [sets] (each with its
 * optional tiebreak from `match_sets`/`match_set_tiebreaks`). This is the shape `MatchRepository` returns
 * — only the repository can run the extra child queries, so it bundles them here and the `mapper.entity`
 * conversion builds the domain `Match` (parsing the enum columns and attaching the children) with no
 * further DB access. Kept **model-free** so `persistence` stays a leaf.
 */
data class MatchAggregateEntity(
    val match: MatchEntity,
    val team1: MatchSideEntity,
    val team2: MatchSideEntity,
    val sets: List<MatchSetEntity>,
)
