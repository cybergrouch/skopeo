// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

// Entity→domain mappers for the match aggregate (#633): builds the domain Match from the raw
// MatchAggregateEntity graph the repository returns, plus the per-row MatchSideEntity/MatchSetEntity
// conversions. This is the single boundary where the stored enum strings
// (TeamType/MatchType/MatchStatus/PlacementBracket) are parsed and the child sides/sets attached. Lives
// in mapper.entity (which may depend on persistence + model); the service calls it, since
// repository ↛ mapper.

package org.skopeo.domain.mapper.entity.match

import org.skopeo.domain.model.Match
import org.skopeo.domain.model.MatchCompletionReason
import org.skopeo.domain.model.MatchSetResult
import org.skopeo.domain.model.MatchSide
import org.skopeo.domain.model.MatchStatus
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.PlacementBracket
import org.skopeo.domain.model.TeamType
import org.skopeo.repository.persistence.MatchAggregateEntity
import org.skopeo.repository.persistence.MatchSetEntity
import org.skopeo.repository.persistence.MatchSideEntity
import java.util.UUID

// Convert a raw MatchSideEntity to the domain MatchSide (a straight field copy).
fun MatchSideEntity.toDomain(): MatchSide =
    MatchSide(
        teamId = teamId,
        userIds = userIds,
    )

/**
 * Convert a raw `MatchSetEntity` to the domain `MatchSetResult`, **deriving** the set winner rather than
 * copying the stored one (#917).
 *
 * Who won a set is a pure function of that set's own games and tiebreak points, so
 * `match_sets.winner_team_id` is a stored derivation — a second source of truth for something the row
 * already determines, with nothing preventing the two from disagreeing. Deriving here makes the games
 * the only authority and leaves the column unread ahead of dropping it.
 *
 * This is the *derived* winner, and it is distinct from the **designated** match winner on `matches`.
 * Today they always agree because both come from the sets; a retirement (#911) is the first case where
 * they legitimately differ — the opponent is awarded the match while the retiring player may have been
 * leading the unfinished set, and the rating should follow the tennis played.
 */
fun MatchSetEntity.toDomain(
    team1Id: UUID,
    team2Id: UUID,
): MatchSetResult =
    MatchSetResult(
        setNumber = setNumber,
        team1Games = team1Games,
        team2Games = team2Games,
        winnerTeamId = derivedSetWinner(team1Id = team1Id, team2Id = team2Id),
        tiebreakTeam1Points = tiebreakTeam1Points,
        tiebreakTeam2Points = tiebreakTeam2Points,
        abandoned = abandoned,
    )

/**
 * Games decide the set; a tied set falls through to the tiebreak points. Mirrors the derivation applied
 * at the recording boundary (`MatchService.setWinner`), which is the only other place the rule lives.
 *
 * **Throws on an undecidable set**, which is unreachable for stored data: `setWinner` refuses to record
 * a set that is tied on games with no deciding tiebreak, and V53 dropped the stored winner only after
 * confirming production held none (959 sets, zero tied on games, zero tiebreaks). There is no longer a
 * stored value to fall back to, and silently picking a side would put a fabricated result into the
 * rating pipeline — failing loudly with the set number is the honest alternative.
 */
private fun MatchSetEntity.derivedSetWinner(
    team1Id: UUID,
    team2Id: UUID,
): UUID? =
    when {
        team1Games > team2Games -> team1Id
        team2Games > team1Games -> team2Id
        tiebreakTeam1Points != null && tiebreakTeam2Points != null && tiebreakTeam1Points != tiebreakTeam2Points ->
            if (tiebreakTeam1Points > tiebreakTeam2Points) team1Id else team2Id
        // Nobody won it (#968). This used to throw, on the premise that such a set was unrecordable —
        // true until a retirement in a level set needed its games kept. Throwing now would make the
        // match unreadable rather than protecting anything: the row exists, and null is what it means.
        else -> null
    }

// Build the domain Match from the raw MatchAggregateEntity graph the repository returns: the `matches`
// scalars plus the loaded sides/sets. The single boundary where the stored enum strings are parsed and
// the children attached.
fun MatchAggregateEntity.toDomain(): Match =
    Match(
        id = match.id,
        publicCode = match.publicCode,
        matchFormat = TeamType.valueOf(value = match.matchFormat),
        matchType = MatchType.valueOf(value = match.matchType),
        matchDate = match.matchDate,
        status = MatchStatus.valueOf(value = match.status),
        completionReason = MatchCompletionReason.valueOf(value = match.completionReason),
        team1 = team1.toDomain(),
        team2 = team2.toDomain(),
        winnerTeamId = match.winnerTeamId,
        sets = sets.map { it.toDomain(team1Id = team1.teamId, team2Id = team2.teamId) },
        venue = match.venue,
        tournamentName = match.tournamentName,
        isActive = match.isActive,
        completedAt = match.completedAt,
        ratedAt = match.ratedAt,
        createdBy = match.createdBy,
        recordedBy = match.recordedBy,
        eventId = match.eventId,
        matchNumber = match.matchNumber,
        team1Handicap = match.team1Handicap,
        team2Handicap = match.team2Handicap,
        isPlacementMatch = match.isPlacementMatch,
        placementBracket = match.placementBracket?.let { PlacementBracket.valueOf(value = it) },
        reRatedAt = match.reRatedAt,
        reRatedCount = match.reRatedCount,
    )
