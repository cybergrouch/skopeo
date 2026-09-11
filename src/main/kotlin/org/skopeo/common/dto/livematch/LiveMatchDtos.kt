// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.dto.livematch

import kotlinx.serialization.Serializable

/**
 * Body for `POST /api/v1/matches/{id}/live/events` — one umpire action (#911).
 *
 * [kind] is validated in the service, not here: the set of kinds is a domain concept and `dto` must not
 * reach into `model` to enumerate it. An unknown kind is a 400 with the permitted list.
 *
 * [side] and [playerId] are the payload, and which of them applies depends on [kind]. Supplying the
 * wrong one is a 400 rather than being ignored — silently dropping a field the caller meant is how a
 * scoreboard ends up quietly wrong.
 */
@Serializable
data class LiveScoreEventRequest(
    val kind: String,
    val side: String? = null,
    val playerId: String? = null,
)

/**
 * A player in the match, for the umpire's server picker (#943).
 *
 * On the live response rather than looked up separately because the umpire view is addressed by match
 * and already holds this response — and `MatchPublicPlayer` carries no user id, so there is nothing to
 * map a `serverId` back to without it.
 */
@Serializable
data class LivePlayerResponse(
    val userId: String,
    val name: String,
    val side: String,
)

/** One banked set in the live view. Mirrors what will become a `MatchSetResult` at finalize. */
@Serializable
data class LiveSetResponse(
    val gamesTeam1: Int,
    val gamesTeam2: Int,
    val winner: String,
    val tiebreakTeam1Points: Int? = null,
    val tiebreakTeam2Points: Int? = null,
)

/** How a live match ended, once it has. */
@Serializable
data class LiveOutcomeResponse(
    val kind: String,
    val winner: String,
    val concededBy: String? = null,
)

/**
 * The live scoreboard (#911).
 *
 * [pointsTeam1]/[pointsTeam2] are **rendered** (`0`/`15`/`30`/`40`/`AD`, or a plain ordinal in a
 * tiebreak) rather than raw counts, so every client shows the same thing and none of them re-implements
 * deuce. The raw counts are deliberately not exposed: they are an engine detail, and a client that had
 * them would eventually derive its own scoreline from them.
 *
 * [sequence] is how far the log has got. A client holding a lower number knows it is behind, and it is
 * the same value the spectator projection carries so a late-arriving broadcast cannot overwrite a newer
 * one.
 */
@Serializable
data class LiveMatchResponse(
    val matchId: String,
    val sequence: Long,
    val scorerId: String? = null,
    val hasStarted: Boolean,
    val isPaused: Boolean,
    /**
     * A set has been awarded and the next has not begun (#984).
     *
     * The umpire's decision point: start the next set, or finalize here. Scoring is refused until one
     * of those happens, so the view greys the board rather than letting taps fail.
     */
    val isBetweenSets: Boolean = false,
    val isTiebreak: Boolean,
    val serverId: String? = null,
    /** The server's display name, so a client need not resolve the id itself. */
    val serverName: String? = null,
    /** Both sides' players, for the server picker. Static per match; small enough to ride along. */
    val players: List<LivePlayerResponse> = emptyList(),
    val pointsTeam1: String,
    val pointsTeam2: String,
    val gamesTeam1: Int,
    val gamesTeam2: Int,
    val sets: List<LiveSetResponse>,
    /**
     * Playing time so far in seconds, **excluding every suspension** (#937).
     *
     * Measured from `MATCH_STARTED` rather than the first point — the gap between an umpire opening the
     * app and the players starting is exactly what would corrupt the figure. Frozen once the match ends,
     * so a finished match does not keep accruing minutes while nobody finalizes it.
     */
    val elapsedSeconds: Long = 0,
    /**
     * Whether the clock is advancing. False before the start, while paused, and once the match has ended.
     *
     * A client ticks locally while this is true and stops when it is not, rather than polling — the value
     * is re-synced by every response, and between responses a local tick is both accurate and free of
     * clock skew.
     */
    val isRunning: Boolean = false,
    val outcome: LiveOutcomeResponse? = null,
)
