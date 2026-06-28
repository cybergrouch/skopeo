// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import org.skopeo.dto.user.OpponentSummary
import org.skopeo.dto.user.PlayerMatchHistoryEntry
import org.skopeo.dto.user.PublicPlayerResponse
import org.skopeo.dto.user.PublicRatingDto
import org.skopeo.model.Capability
import org.skopeo.model.Match
import org.skopeo.model.RatingHistoryEntry
import org.skopeo.model.ServiceError
import org.skopeo.model.User
import org.skopeo.model.displayName
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import java.util.UUID

/**
 * Resolves a player's shareable, auth-gated public profile from their [public code] (issue #61).
 * Open to any authenticated user (the route is behind auth); returns only a privacy-conscious
 * subset, not the full account.
 *
 * Expected failures are returned as an [Either] left ([ServiceError], issue #115) rather than thrown.
 */
class PlayerService(
    private val users: UserRepository = UserRepository(),
    private val ratings: RatingRepository = RatingRepository(),
    private val matches: MatchRepository = MatchRepository(),
) {
    fun publicProfile(code: String): Either<ServiceError, PublicPlayerResponse> =
        either {
            val located = locate(code = code).bind()
            if (!located.isActive) {
                mergedCard(located = located).bind()
            } else {
                val rating = ratings.findCurrentRating(userId = located.id)
                PublicPlayerResponse(
                    publicCode = located.publicCode,
                    displayName = located.displayName(),
                    photoUrl = located.photoUrl,
                    rating = rating?.let { PublicRatingDto(value = it.currentRating.toPlainString(), level = it.currentLevel) },
                )
            }
        }

    /**
     * A disabled duplicate (#124) renders a "merged" card linking to its canonical account; a
     * plain-deactivated account (no canonical) stays hidden (treated as not-found).
     */
    private fun mergedCard(located: User): Either<ServiceError, PublicPlayerResponse> {
        val canonical =
            located.canonicalUserId?.let { users.findById(id = it).getOrNull() }
                ?: return ServiceError.NotFound(message = "No player with code ${located.publicCode}").left()
        return PublicPlayerResponse(
            publicCode = located.publicCode,
            displayName = located.displayName(),
            photoUrl = located.photoUrl,
            rating = null,
            isDisabled = true,
            canonical =
                OpponentSummary(
                    publicCode = canonical.publicCode,
                    displayName = canonical.displayName(),
                    photoUrl = canonical.photoUrl,
                ),
        ).right()
    }

    /**
     * The player's match history (issue #65): every active match they played, newest first, with the
     * opponent, result, and — for rated matches — each side's NTRP band at the time. Reconstructed
     * from rating history (each rated match writes one history row per player carrying their pre-match
     * level), so no per-match snapshot column is needed.
     */
    fun matchHistory(code: String): Either<ServiceError, List<PlayerMatchHistoryEntry>> =
        either {
            val user = resolve(code = code).bind()
            // A canonical account's history also surfaces its disabled duplicates' matches (#124,
            // display-only — ratings are never consolidated). Each match is oriented from whichever of
            // these "self" ids actually played it.
            val selfIds = (listOf(element = user.id) + users.findDuplicatesOf(canonicalId = user.id).map { it.id }).toSet()
            val played =
                selfIds
                    .flatMap { matches.listByUser(userId = it) }
                    .distinctBy { it.id }
                    .sortedByDescending { it.matchDate }
            val ratedMatchIds = played.filter { it.ratedAt != null }.map { it.id }
            val levelByMatchAndUser =
                ratings
                    .historyForMatches(matchIds = ratedMatchIds)
                    .groupBy { it.matchId }
                    .mapValues { (_, rows) -> rows.associate { it.userId to it.previousLevel } }
            val participantByMatch = played.associate { it.id to participantOf(match = it, selfIds = selfIds) }
            val opponents =
                users
                    .findAllByIds(ids = played.map { opponentId(match = it, playerId = participantByMatch.getValue(key = it.id)) })
                    .associateBy { it.id }
            played.map { match ->
                val participant = participantByMatch.getValue(key = match.id)
                entry(match = match, playerId = participant, opponents = opponents, levels = levelByMatchAndUser[match.id])
            }
        }

    /**
     * A player's full rating history by code, for ADMINISTRATORs only (issue #73). The owner reads
     * their own history via the user-id endpoint; this code-based variant exists so an admin viewing
     * a public profile can see it. Unlike match history, this is the precise audit view.
     */
    fun ratingHistory(
        token: VerifiedFirebaseToken,
        code: String,
    ): Either<ServiceError, List<RatingHistoryEntry>> =
        either {
            requireAdmin(token = token).bind()
            val user = resolve(code = code).bind()
            ratings.historyByUser(userId = user.id)
        }

    private fun requireAdmin(token: VerifiedFirebaseToken): Either<ServiceError, Unit> {
        val caller = users.findByFirebaseUid(firebaseUid = token.uid)
        return if (caller == null || Capability.ADMINISTRATOR !in caller.capabilities) {
            ServiceError.Forbidden().left()
        } else {
            Unit.right()
        }
    }

    private fun resolve(code: String): Either<ServiceError, User> {
        val normalized = code.trim().uppercase()
        return users.findByPublicCode(code = normalized)?.takeIf { it.isActive }?.right()
            ?: ServiceError.NotFound(message = "No player with code $normalized").left()
    }

    /** Find by code regardless of active status (a disabled duplicate still has a viewable card); 404 if unknown. */
    private fun locate(code: String): Either<ServiceError, User> {
        val normalized = code.trim().uppercase()
        return users.findByPublicCode(code = normalized)?.right()
            ?: ServiceError.NotFound(message = "No player with code $normalized").left()
    }

    /** The "self" id (canonical or one of its duplicates) that actually played [match]. */
    private fun participantOf(
        match: Match,
        selfIds: Set<UUID>,
    ): UUID = (match.team1.userIds + match.team2.userIds).first { it in selfIds }

    private fun entry(
        match: Match,
        playerId: UUID,
        opponents: Map<UUID, User>,
        levels: Map<UUID, String?>?,
    ): PlayerMatchHistoryEntry {
        val onTeam1 = playerId in match.team1.userIds
        val playerTeamId = if (onTeam1) match.team1.teamId else match.team2.teamId
        val oppId = opponentId(match = match, playerId = playerId)
        val opp = opponents.getValue(key = oppId)
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
            opponent = OpponentSummary(publicCode = opp.publicCode, displayName = opp.displayName(), photoUrl = opp.photoUrl),
            playerLevelAtMatch = levels?.get(key = playerId),
            opponentLevelAtMatch = levels?.get(key = oppId),
        )
    }

    /** The opposing player's id. A singles match always has exactly one opponent (enforced on creation). */
    private fun opponentId(
        match: Match,
        playerId: UUID,
    ): UUID {
        val otherSide = if (playerId in match.team1.userIds) match.team2 else match.team1
        return otherSide.userIds.first()
    }
}
