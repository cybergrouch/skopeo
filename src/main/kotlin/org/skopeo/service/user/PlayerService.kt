// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import org.skopeo.dto.user.OpponentSummary
import org.skopeo.dto.user.PlayerMatchHistoryEntry
import org.skopeo.dto.user.PublicPlayerResponse
import org.skopeo.dto.user.PublicRatingDto
import org.skopeo.model.Match
import org.skopeo.model.User
import org.skopeo.model.displayName
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import org.skopeo.service.ResourceNotFoundException
import java.util.UUID

/**
 * Resolves a player's shareable, auth-gated public profile from their [public code] (issue #61).
 * Open to any authenticated user (the route is behind auth); returns only a privacy-conscious
 * subset, not the full account.
 */
class PlayerService(
    private val users: UserRepository = UserRepository(),
    private val ratings: RatingRepository = RatingRepository(),
    private val matches: MatchRepository = MatchRepository(),
) {
    fun publicProfile(code: String): PublicPlayerResponse {
        val user = resolve(code = code)
        val rating = ratings.findCurrentRating(userId = user.id)
        return PublicPlayerResponse(
            publicCode = user.publicCode,
            displayName = user.displayName(),
            photoUrl = user.photoUrl,
            rating = rating?.let { PublicRatingDto(value = it.currentRating.toPlainString(), level = it.currentLevel) },
        )
    }

    /**
     * The player's match history (issue #65): every active match they played, newest first, with the
     * opponent, result, and — for rated matches — each side's NTRP band at the time. Reconstructed
     * from rating history (each rated match writes one history row per player carrying their pre-match
     * level), so no per-match snapshot column is needed.
     */
    fun matchHistory(code: String): List<PlayerMatchHistoryEntry> {
        val user = resolve(code = code)
        val played = matches.listByUser(userId = user.id)
        val ratedMatchIds = played.filter { it.ratedAt != null }.map { it.id }
        val levelByMatchAndUser =
            ratings
                .historyForMatches(matchIds = ratedMatchIds)
                .groupBy { it.matchId }
                .mapValues { (_, rows) -> rows.associate { it.userId to it.previousLevel } }
        val opponents =
            users
                .findAllByIds(ids = played.mapNotNull { opponentId(match = it, playerId = user.id) })
                .associateBy { it.id }
        return played.map { match ->
            entry(match = match, playerId = user.id, opponents = opponents, levels = levelByMatchAndUser[match.id])
        }
    }

    private fun resolve(code: String): User {
        val normalized = code.trim().uppercase()
        return users.findByPublicCode(code = normalized)?.takeIf { it.isActive }
            ?: throw ResourceNotFoundException(message = "No player with code $normalized")
    }

    private fun entry(
        match: Match,
        playerId: UUID,
        opponents: Map<UUID, User>,
        levels: Map<UUID, String?>?,
    ): PlayerMatchHistoryEntry {
        val onTeam1 = playerId in match.team1.userIds
        val playerTeamId = if (onTeam1) match.team1.teamId else match.team2.teamId
        val oppId = opponentId(match = match, playerId = playerId)
        val opp = oppId?.let { opponents[it] }
        return PlayerMatchHistoryEntry(
            matchId = match.id.toString(),
            matchDate = match.matchDate.toString(),
            status = match.status.name,
            rated = match.ratedAt != null,
            result = match.winnerTeamId?.let { if (it == playerTeamId) "WIN" else "LOSS" },
            setScores =
                match.sets.map { set ->
                    val playerGames = if (onTeam1) set.team1Games else set.team2Games
                    val opponentGames = if (onTeam1) set.team2Games else set.team1Games
                    "$playerGames-$opponentGames"
                },
            opponent =
                opp?.let {
                    OpponentSummary(publicCode = it.publicCode, displayName = it.displayName(), photoUrl = it.photoUrl)
                },
            playerLevelAtMatch = levels?.get(key = playerId),
            opponentLevelAtMatch = oppId?.let { levels?.get(key = it) },
        )
    }

    private fun opponentId(
        match: Match,
        playerId: UUID,
    ): UUID? {
        val otherSide = if (playerId in match.team1.userIds) match.team2 else match.team1
        return otherSide.userIds.firstOrNull()
    }
}
