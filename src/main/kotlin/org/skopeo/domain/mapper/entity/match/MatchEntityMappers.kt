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
import org.skopeo.domain.model.MatchSetResult
import org.skopeo.domain.model.MatchSide
import org.skopeo.domain.model.MatchStatus
import org.skopeo.domain.model.MatchType
import org.skopeo.domain.model.PlacementBracket
import org.skopeo.domain.model.TeamType
import org.skopeo.repository.persistence.MatchAggregateEntity
import org.skopeo.repository.persistence.MatchSetEntity
import org.skopeo.repository.persistence.MatchSideEntity

// Convert a raw MatchSideEntity to the domain MatchSide (a straight field copy).
fun MatchSideEntity.toDomain(): MatchSide =
    MatchSide(
        teamId = teamId,
        userIds = userIds,
    )

// Convert a raw MatchSetEntity to the domain MatchSetResult (a straight field copy).
fun MatchSetEntity.toDomain(): MatchSetResult =
    MatchSetResult(
        setNumber = setNumber,
        team1Games = team1Games,
        team2Games = team2Games,
        winnerTeamId = winnerTeamId,
        tiebreakTeam1Points = tiebreakTeam1Points,
        tiebreakTeam2Points = tiebreakTeam2Points,
    )

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
        team1 = team1.toDomain(),
        team2 = team2.toDomain(),
        winnerTeamId = match.winnerTeamId,
        sets = sets.map { it.toDomain() },
        venue = match.venue,
        tournamentName = match.tournamentName,
        isActive = match.isActive,
        completedAt = match.completedAt,
        ratedAt = match.ratedAt,
        createdBy = match.createdBy,
        recordedBy = match.recordedBy,
        eventId = match.eventId,
        calcSequence = match.calcSequence,
        team1Handicap = match.team1Handicap,
        team2Handicap = match.team2Handicap,
        isPlacementMatch = match.isPlacementMatch,
        placementBracket = match.placementBracket?.let { PlacementBracket.valueOf(value = it) },
        reRatedAt = match.reRatedAt,
        reRatedCount = match.reRatedCount,
    )
