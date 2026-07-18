// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** Lifecycle of a match record (mirrors the matches.status CHECK). */
enum class MatchStatus { SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED }

/**
 * One of a player's COMPLETED matches inside a confidence window (#459): the [matchDate] it was played
 * and the competitive [weightClass] it counts toward. These rows are the raw inputs to the 3-factor
 * recency × sparsity × spacing confidence in [confidenceAt]: the dates drive recency (freshness of the
 * latest match) and spacing (the biggest hole *between* matches), while the weight classes drive the
 * weighted density. An empty list means no qualifying play in the window → confidence 0.
 */
data class WindowMatch(
    val matchDate: LocalDate,
    val weightClass: WeightClass,
)

/** The confidence weight class a [MatchType] maps to (#459) — tournament, league, or open play. */
enum class WeightClass { TOURNAMENT, LEAGUE, OPEN_PLAY }

/**
 * Which confidence weight class (#459) a match's [MatchType] counts toward: the tournament rounds are
 * TOURNAMENT, the league rounds are LEAGUE, and casual play is OPEN_PLAY. Playoffs share their parent
 * class' weight.
 */
fun MatchType.weightClass(): WeightClass =
    when (this) {
        MatchType.TOURNAMENT_INITIAL_ROUND, MatchType.TOURNAMENT_PLAYOFFS -> WeightClass.TOURNAMENT
        MatchType.LEAGUE_PLAY, MatchType.LEAGUE_PLAYOFFS -> WeightClass.LEAGUE
        MatchType.OPEN_PLAY -> WeightClass.OPEN_PLAY
    }

/**
 * A player's decided win–loss record (#342), aggregated across singles and doubles. [total] is the
 * count of decided matches (a decided match is always a win or a loss — tennis has no ties).
 */
data class WinLossRecord(
    val wins: Int,
    val losses: Int,
) {
    val total: Int get() = wins + losses
}

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
    val eventId: UUID? = null,
    // Manual same-date ordering tiebreaker for the rating calculation (#331/#332); null = default.
    val calcSequence: Int? = null,
    // Points designated for the winner (#403 Phase C); null for OPEN_PLAY / event-less fixtures. Each
    // winning-team member gets the full amount, so the budget cost is designatedPoints × team size.
    val designatedPoints: Int? = null,
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
    val eventId: UUID? = null,
    // Points designated for the winner (#403 Phase C); null for OPEN_PLAY / event-less fixtures.
    val designatedPoints: Int? = null,
)

/** A set's raw score as submitted with results; winners are derived server-side. */
data class SetScoreInput(
    val team1Games: Int,
    val team2Games: Int,
    val tiebreakTeam1Points: Int? = null,
    val tiebreakTeam2Points: Int? = null,
)

/**
 * Which view of fixtures is being requested. PENDING_CALCULATION = completed & unrated;
 * AWAITING_RESULTS = scheduled; RESULTS = an event's completed fixtures (rated or not), so a rated
 * match still appears under its event as a read-only record (#138).
 */
enum class MatchQuery { PENDING_CALCULATION, AWAITING_RESULTS, RESULTS }

/** A match's shareable identity (#136) — enough to resolve an audit target to a public-page link. */
data class MatchPublicRef(
    val matchId: UUID,
    val publicCode: String,
    val matchDate: LocalDate,
)
