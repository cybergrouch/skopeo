// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.match

import kotlinx.serialization.Serializable
import org.skopeo.model.Match
import java.util.UUID

/**
 * The fewest games a side needs to win a set (#213). Standard tennis is 6; Skopeo allows a lower
 * floor of 4 so Hosts can run shortened, schedule-driven formats. Tiebreak-decided sets are exempt.
 */
private const val MIN_GAMES_TO_WIN = 4

/** Body for `POST /api/v1/matches` — create a fixture (no results yet). */
@Serializable
data class CreateFixtureRequest(
    val matchFormat: String,
    val matchType: String,
    val matchDate: String,
    val team1: List<String>,
    val team2: List<String>,
    val venue: String? = null,
    val tournamentName: String? = null,
    /** When set, the fixture belongs to this event and both sides must be participants (#138). */
    val eventId: String? = null,
)

@Serializable
data class SetScoreRequest(
    val team1Games: Int,
    val team2Games: Int,
    val tiebreakTeam1Points: Int? = null,
    val tiebreakTeam2Points: Int? = null,
) {
    init {
        // Shape validation at the boundary (#116): games can never be negative.
        require(value = team1Games >= 0 && team2Games >= 0) { "games must be non-negative" }
        // A set decided on games must be won with at least MIN_GAMES_TO_WIN games (#213). Sets with
        // equal games are decided by the tiebreak (e.g. a match tiebreak) and are exempt from the floor.
        if (team1Games != team2Games) {
            val winnerGames = if (team1Games > team2Games) team1Games else team2Games
            require(value = winnerGames >= MIN_GAMES_TO_WIN) {
                "a set won on games must be won with at least $MIN_GAMES_TO_WIN games"
            }
        }
    }
}

/** Body for `POST /api/v1/matches/{id}/result` — upload the set scores. */
@Serializable
data class MatchResultRequest(
    val sets: List<SetScoreRequest>,
) {
    init {
        // Shape validation at the boundary (#116): a result must report at least one set.
        require(value = sets.isNotEmpty()) { "at least one set is required" }
    }
}

/** Body for `PUT /api/v1/matches/{id}/state` — enable/disable (append-only corrections). */
@Serializable
data class MatchStateRequest(
    val isActive: Boolean,
)

@Serializable
data class MatchSideResponse(
    val teamId: String,
    val userIds: List<String>,
)

@Serializable
data class MatchSetResponse(
    val setNumber: Int,
    val team1Games: Int,
    val team2Games: Int,
    val winnerTeamId: String,
    val tiebreakTeam1Points: Int? = null,
    val tiebreakTeam2Points: Int? = null,
)

@Serializable
data class MatchResponse(
    val id: String,
    val publicCode: String,
    val matchFormat: String,
    val matchType: String,
    val matchDate: String,
    val status: String,
    val team1: MatchSideResponse,
    val team2: MatchSideResponse,
    val winnerTeamId: String? = null,
    val sets: List<MatchSetResponse>,
    val venue: String? = null,
    val tournamentName: String? = null,
    val isActive: Boolean,
    val completedAt: String? = null,
    val ratedAt: String? = null,
    val createdBy: String? = null,
    val recordedBy: String? = null,
    val eventId: String? = null,
)

fun Match.toResponse(): MatchResponse =
    MatchResponse(
        id = id.toString(),
        publicCode = publicCode,
        matchFormat = matchFormat.name,
        matchType = matchType.name,
        matchDate = matchDate.toString(),
        status = status.name,
        team1 = MatchSideResponse(teamId = team1.teamId.toString(), userIds = team1.userIds.map { it.toString() }),
        team2 = MatchSideResponse(teamId = team2.teamId.toString(), userIds = team2.userIds.map { it.toString() }),
        winnerTeamId = winnerTeamId?.toString(),
        sets =
            sets.map {
                MatchSetResponse(
                    setNumber = it.setNumber,
                    team1Games = it.team1Games,
                    team2Games = it.team2Games,
                    winnerTeamId = it.winnerTeamId.toString(),
                    tiebreakTeam1Points = it.tiebreakTeam1Points,
                    tiebreakTeam2Points = it.tiebreakTeam2Points,
                )
            },
        venue = venue,
        tournamentName = tournamentName,
        isActive = isActive,
        completedAt = completedAt?.toString(),
        ratedAt = ratedAt?.toString(),
        createdBy = createdBy?.toString(),
        recordedBy = recordedBy?.toString(),
        eventId = eventId?.toString(),
    )

/** One player on the public match page (#136): just a display name + shareable code, no ids/contacts. */
@Serializable
data class MatchPublicPlayer(
    val displayName: String? = null,
    val publicCode: String? = null,
)

/**
 * A caller's upcoming (scheduled, not-yet-played) match for their private profile (#251): the
 * opponent(s) + date, linking to the public match page by [publicCode]. Owner-only.
 */
@Serializable
data class UpcomingMatchResponse(
    val publicCode: String,
    val matchDate: String,
    val matchType: String,
    val venue: String? = null,
    val opponents: List<MatchPublicPlayer>,
)

/** A set's score on the public match page, expressed per side (no internal team ids). */
@Serializable
data class MatchPublicSet(
    val setNumber: Int,
    val team1Games: Int,
    val team2Games: Int,
    val tiebreakTeam1Points: Int? = null,
    val tiebreakTeam2Points: Int? = null,
)

/**
 * Read-only public summary of a match (#136), addressed by its public code. Players are resolved to
 * display name + code (so the page can link to their public profiles); the winner is named by side.
 */
@Serializable
data class MatchPublicResponse(
    val publicCode: String,
    val matchFormat: String,
    val matchType: String,
    val matchDate: String,
    val status: String,
    // False once the match has been soft-deleted (#325): its link stays honored for traceability, and
    // the public page flags it as deleted.
    val isActive: Boolean = true,
    val team1: List<MatchPublicPlayer>,
    val team2: List<MatchPublicPlayer>,
    // The winning side, named relative to team1/team2: "TEAM1" | "TEAM2" | "NONE".
    val winner: String,
    val sets: List<MatchPublicSet>,
    val venue: String? = null,
    val tournamentName: String? = null,
    // Per-player rating change, present only once the match is rated. The NTRP bands (previous/new
    // level) are shown to everyone; the precise rates are populated only for RATER/ADMINISTRATOR viewers.
    val ratingChanges: List<MatchPublicRatingChange>? = null,
    // Prior meetings between the same two players (#188); null when there are none or the match is not
    // singles. Wins and set scores are oriented to team1/team2 of THIS match.
    val headToHead: MatchPublicHeadToHead? = null,
)

/**
 * Head-to-head record between the two players of a singles match (#188): the win tally and the prior
 * completed meetings, newest first. [team1Wins]/[team2Wins] and each meeting's set scores are oriented
 * to team1/team2 of the match being viewed, so the orientation is stable across rows.
 */
@Serializable
data class MatchPublicHeadToHead(
    val team1Wins: Int,
    val team2Wins: Int,
    val meetings: List<MatchPublicHeadToHeadEntry>,
)

/**
 * One prior meeting in a head-to-head record (#188). [sets] are oriented to team1/team2 of the match
 * being viewed; [winnerPublicCode] is the winning player's code (one of the two), or null if undecided.
 * [matchFormat] (SINGLES/DOUBLES/MIXED_DOUBLES) lets the card show whether the meeting was singles or
 * doubles (#285).
 */
@Serializable
data class MatchPublicHeadToHeadEntry(
    val publicCode: String,
    val matchDate: String,
    val status: String,
    val rated: Boolean,
    val matchFormat: String,
    val sets: List<MatchPublicSet>,
    val winnerPublicCode: String? = null,
)

/**
 * One player's rating change for a rated match (#136). [previousLevel]/[newLevel] are the public NTRP
 * bands (shown to everyone). [previousRating]/[newRating]/[ratingChange] are the precise values (NUMERIC
 * 10,6 → 6 fractional digits) and are null unless the viewer is a RATER or ADMINISTRATOR.
 */
@Serializable
data class MatchPublicRatingChange(
    val publicCode: String? = null,
    val displayName: String? = null,
    val previousLevel: String? = null,
    val newLevel: String? = null,
    val previousRating: String? = null,
    val newRating: String? = null,
    val ratingChange: String? = null,
)

/** Build the public response, resolving each side's players via [players] (id → name/code). */
fun Match.toPublicResponse(
    players: Map<UUID, MatchPublicPlayer>,
    ratingChanges: List<MatchPublicRatingChange>? = null,
    headToHead: MatchPublicHeadToHead? = null,
): MatchPublicResponse {
    fun side(userIds: List<UUID>) = userIds.map { players[it] ?: MatchPublicPlayer() }
    val winnerSide =
        when (winnerTeamId) {
            team1.teamId -> "TEAM1"
            team2.teamId -> "TEAM2"
            else -> "NONE"
        }
    return MatchPublicResponse(
        publicCode = publicCode,
        matchFormat = matchFormat.name,
        matchType = matchType.name,
        matchDate = matchDate.toString(),
        status = status.name,
        isActive = isActive,
        team1 = side(userIds = team1.userIds),
        team2 = side(userIds = team2.userIds),
        winner = winnerSide,
        sets =
            sets.map {
                MatchPublicSet(
                    setNumber = it.setNumber,
                    team1Games = it.team1Games,
                    team2Games = it.team2Games,
                    tiebreakTeam1Points = it.tiebreakTeam1Points,
                    tiebreakTeam2Points = it.tiebreakTeam2Points,
                )
            },
        venue = venue,
        tournamentName = tournamentName,
        ratingChanges = ratingChanges,
        headToHead = headToHead,
    )
}
