// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** Lifecycle of a match record (mirrors the matches.status CHECK). */
enum class MatchStatus { SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED }

/** One side of a match: a (temporary) team and its participating users, in position order. */
data class MatchSide(
    val teamId: UUID,
    val userIds: List<UUID>,
)

/** A completed set's score, with an optional tiebreak. Winner is derived from the games/tiebreak. */
data class MatchSetResult(
    val setNumber: Int,
    val team1Games: Int,
    val team2Games: Int,
    val winnerTeamId: UUID,
    val tiebreakTeam1Points: Int? = null,
    val tiebreakTeam2Points: Int? = null,
)

/**
 * A match as stored — a fixture (scheduled) that later gains results (completed) and is then
 * rated by the calculation trigger. Append-only: corrections disable the original and add a new
 * one. [winnerTeamId]/[sets]/[completedAt] are null/empty until results are uploaded;
 * [ratedAt] is null until the rating calculation commits it.
 */
data class Match(
    val id: UUID,
    val publicCode: String,
    val matchFormat: TeamType,
    val matchType: MatchType,
    val matchDate: LocalDate,
    val status: MatchStatus,
    val team1: MatchSide,
    val team2: MatchSide,
    val winnerTeamId: UUID?,
    val sets: List<MatchSetResult>,
    val venue: String? = null,
    val tournamentName: String? = null,
    val isActive: Boolean = true,
    val completedAt: LocalDateTime? = null,
    val ratedAt: LocalDateTime? = null,
    val createdBy: UUID? = null,
    val recordedBy: UUID? = null,
)

/** Everything needed to create a fixture (the result is uploaded separately). */
data class CreateFixtureCommand(
    val matchFormat: TeamType,
    val matchType: MatchType,
    val matchDate: LocalDate,
    val team1UserIds: List<UUID>,
    val team2UserIds: List<UUID>,
    val team1Name: String,
    val team2Name: String,
    val createdBy: UUID,
    val venue: String? = null,
    val tournamentName: String? = null,
)

/** A set's raw score as submitted with results; winners are derived server-side. */
data class SetScoreInput(
    val team1Games: Int,
    val team2Games: Int,
    val tiebreakTeam1Points: Int? = null,
    val tiebreakTeam2Points: Int? = null,
)

/** Which oversight view of fixtures an administrator is requesting. */
enum class MatchQuery { PENDING_CALCULATION, AWAITING_RESULTS }

/** A match's shareable identity (#136) — enough to resolve an audit target to a public-page link. */
data class MatchPublicRef(
    val matchId: UUID,
    val publicCode: String,
    val matchDate: LocalDate,
)
