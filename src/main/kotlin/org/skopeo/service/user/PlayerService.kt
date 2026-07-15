// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import org.skopeo.dto.user.MatchHistoryParticipant
import org.skopeo.dto.user.OpponentSummary
import org.skopeo.dto.user.PlayerMatchHistoryEntry
import org.skopeo.dto.user.PlayerMatchHistoryPage
import org.skopeo.dto.user.PlayerResultsSummary
import org.skopeo.dto.user.PublicPlayerResponse
import org.skopeo.dto.user.PublicRatingDto
import org.skopeo.dto.user.ResultsBucket
import org.skopeo.model.Capability
import org.skopeo.model.Match
import org.skopeo.model.RatingHistoryEntry
import org.skopeo.model.ServiceError
import org.skopeo.model.TeamType
import org.skopeo.model.User
import org.skopeo.model.displayName
import org.skopeo.repository.MatchRepository
import org.skopeo.repository.RatingRepository
import org.skopeo.repository.UserRepository
import java.util.UUID

// Match-history pagination (#284): the default page size and the hard cap, mirroring player search (#232).
private const val DEFAULT_HISTORY_LIMIT = 20
private const val MAX_HISTORY_LIMIT = 100

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
                    rating =
                        rating?.let {
                            PublicRatingDto(
                                value = it.currentRating.toPlainString(),
                                level = it.currentLevel,
                                confidence = it.confidence.toPlainString(),
                            )
                        },
                )
            }
        }

    /**
     * A disabled duplicate (#124) renders a "merged" card linking to its canonical account; a
     * plain-deactivated account (no canonical) stays hidden (treated as not-found).
     */
    private fun mergedCard(located: User): Either<ServiceError, PublicPlayerResponse> {
        val canonicalId = located.canonicalUserId
        val canonical = if (canonicalId == null) null else users.findById(id = canonicalId).getOrNull()
        if (canonical == null) {
            return ServiceError.NotFound(message = "No player with code ${located.publicCode}").left()
        }
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
     * A page of the player's match history (#65, #284), newest first, optionally narrowed by [search] (a
     * case-insensitive substring over opponent/partner display names and public codes). Returns the
     * requested [offset]/[limit] slice plus the total matching count, so the profile can show a bounded
     * preview and a full page can paginate. The full oriented history is assembled server-side (as
     * before) then filtered and sliced; the client no longer loads every row.
     */
    fun matchHistory(
        code: String,
        limit: Int = DEFAULT_HISTORY_LIMIT,
        offset: Int = 0,
        search: String? = null,
    ): Either<ServiceError, PlayerMatchHistoryPage> =
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
            // Resolve every other participant (partners + opponents) across all matches in one lookup.
            val otherIds =
                played.flatMap { match ->
                    val self = participantByMatch.getValue(key = match.id)
                    (match.team1.userIds + match.team2.userIds).filterNot { it == self }
                }
            val participantsById = users.findAllByIds(ids = otherIds).associateBy { it.id }
            val entries =
                played.map { match ->
                    val participant = participantByMatch.getValue(key = match.id)
                    entry(match = match, playerId = participant, players = participantsById, levels = levelByMatchAndUser[match.id])
                }

            // Case-insensitive match on any opponent/partner display name or public code.
            fun rowMatches(
                row: PlayerMatchHistoryEntry,
                needle: String,
            ): Boolean =
                (row.opponents + row.partners).any { p ->
                    p.publicCode.lowercase().contains(other = needle) || p.displayName?.lowercase()?.contains(other = needle) == true
                }

            val matched =
                search?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                    ?.let { needle -> entries.filter { rowMatches(row = it, needle = needle) } }
                    ?: entries
            PlayerMatchHistoryPage(
                items =
                    matched
                        .drop(n = offset.coerceAtLeast(minimumValue = 0))
                        .take(n = limit.coerceIn(minimumValue = 1, maximumValue = MAX_HISTORY_LIMIT)),
                total = matched.size,
            )
        }

    /**
     * A player's win–loss record over time (#276): every decided match (with a recorded winner) they
     * played — a canonical account also including its duplicates' matches (#124) — bucketed by calendar
     * month and split into singles vs doubles (MIXED_DOUBLES counts as doubles). Aggregated server-side
     * so the chart is independent of how match history is listed/paginated. Only band-agnostic W/L
     * counts leave the service — never a rating.
     */
    fun resultsSummary(code: String): Either<ServiceError, PlayerResultsSummary> =
        either {
            val user = resolve(code = code).bind()
            val selfIds = (listOf(element = user.id) + users.findDuplicatesOf(canonicalId = user.id).map { it.id }).toSet()
            val rows =
                selfIds
                    .flatMap { matches.listByUser(userId = it) }
                    .distinctBy { it.id }
                    .filter { it.winnerTeamId != null }
                    .map { match ->
                        val self = participantOf(match = match, selfIds = selfIds)
                        val selfTeamId = if (self in match.team1.userIds) match.team1.teamId else match.team2.teamId
                        ResultRow(
                            singles = match.matchFormat == TeamType.SINGLES,
                            period = match.matchDate.toString().take(n = 7),
                            won = match.winnerTeamId == selfTeamId,
                        )
                    }

            // One ResultsBucket per month (oldest first) for a given format's rows.
            fun bucketsOf(formatRows: List<ResultRow>): List<ResultsBucket> =
                formatRows
                    .groupBy { it.period }
                    .toSortedMap()
                    .map { (period, monthRows) ->
                        ResultsBucket(period = period, wins = monthRows.count { it.won }, losses = monthRows.count { !it.won })
                    }

            PlayerResultsSummary(
                singles = bucketsOf(formatRows = rows.filter { it.singles }),
                doubles = bucketsOf(formatRows = rows.filterNot { it.singles }),
            )
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
        val user = users.findByPublicCode(code = normalized)
        return if (user == null || !user.isActive) {
            ServiceError.NotFound(message = "No player with code $normalized").left()
        } else {
            user.right()
        }
    }

    /** Find by code regardless of active status (a disabled duplicate still has a viewable card); 404 if unknown. */
    private fun locate(code: String): Either<ServiceError, User> {
        val normalized = code.trim().uppercase()
        val user = users.findByPublicCode(code = normalized)
        return if (user == null) ServiceError.NotFound(message = "No player with code $normalized").left() else user.right()
    }

    /** The "self" id (canonical or one of its duplicates) that actually played [match]. */
    private fun participantOf(
        match: Match,
        selfIds: Set<UUID>,
    ): UUID = (match.team1.userIds + match.team2.userIds).first { it in selfIds }

    private fun entry(
        match: Match,
        playerId: UUID,
        players: Map<UUID, User>,
        levels: Map<UUID, String?>?,
    ): PlayerMatchHistoryEntry {
        val onTeam1 = playerId in match.team1.userIds
        val playerTeam = if (onTeam1) match.team1 else match.team2
        val opposingTeam = if (onTeam1) match.team2 else match.team1

        fun participant(userId: UUID): MatchHistoryParticipant {
            val user = players.getValue(key = userId)
            return MatchHistoryParticipant(
                publicCode = user.publicCode,
                displayName = user.displayName(),
                photoUrl = user.photoUrl,
                levelAtMatch = levels?.get(key = userId),
            )
        }

        return PlayerMatchHistoryEntry(
            matchId = match.id.toString(),
            publicCode = match.publicCode,
            matchDate = match.matchDate.toString(),
            status = match.status.name,
            rated = match.ratedAt != null,
            result = match.winnerTeamId?.let { if (it == playerTeam.teamId) "WIN" else "LOSS" },
            setScores =
                match.sets.map { set ->
                    val playerGames = if (onTeam1) set.team1Games else set.team2Games
                    val opponentGames = if (onTeam1) set.team2Games else set.team1Games
                    "$playerGames-$opponentGames"
                },
            partners = playerTeam.userIds.filterNot { it == playerId }.map(transform = ::participant),
            opponents = opposingTeam.userIds.map(transform = ::participant),
            playerLevelAtMatch = levels?.get(key = playerId),
        )
    }
}

/** One decided match reduced to what the results summary needs (#276): format, month, and outcome. */
private data class ResultRow(
    val singles: Boolean,
    val period: String,
    val won: Boolean,
)
