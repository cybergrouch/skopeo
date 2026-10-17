// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.contract

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * The replay of a live-scored match (#1145) — stored as one JSON document in `match_replays` and served
 * unchanged to the match page, so this is both the storage format and the wire contract.
 *
 * ## Versioning
 *
 * - [version] is the format, mirrored in `match_replays.format_version`.
 * - **Additive only.** A later version may add fields; it never renames, removes or redefines one. A new
 *   field is therefore nullable or defaulted here.
 * - **One format in use.** Every bump ships with an upgrade, which regenerates a document from its stored
 *   [events] (`MatchReplayService`). A field that needs an input older matches never captured stays
 *   absent, and the UI hides that feature for those matches — features are gated on data presence, not
 *   on version numbers.
 *
 * [events] is the source and [points] is derived from it, which is what makes regeneration possible
 * after the raw `live_match_events` log has been swept (#939). The web reads [points] and [result] only.
 *
 * ## Who scored each step (v2, #1183)
 *
 * Each event and point carries the **public code** of the umpire who recorded it — never a name, which
 * changes, and never a user id, which is not a public identifier. The endpoint resolves codes to current
 * display names at read time ([ReplayResponse.scorers]) and only for a caller entitled to see them; for
 * everyone else the codes are stripped from the response. A replay regenerated from a v1 document has no
 * codes to carry, so the field is simply absent there.
 */
@Serializable
data class ReplayDocument(
    val version: Int,
    /** The result this replay finishes at, in recorded form: the match page shows the replay only while it still equals the match's. */
    val result: ReplayResult,
    /** The umpire's log with every undone action removed, in order. */
    val events: List<ReplayEvent>,
    /** One step per point (or awarded game/set, or the ending), with the score after it. */
    val points: List<ReplayPoint>,
)

/** One surviving umpire action. [kind] is the stored `live_match_events.kind`; [atMs] is since the first action. */
@Serializable
data class ReplayEvent(
    val kind: String,
    val side: String? = null,
    val atMs: Long,
    /** Public code of the umpire who recorded this action (v2, #1183); absent when not known or not shown. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
    val scorer: String? = null,
)

/**
 * One step of the replay and the score after it.
 *
 * [kind] is POINT for a point played, GAME or SET for one awarded directly by the umpire, and END for the
 * match's ending (completed, retired or defaulted, in [outcome]). [server] is the serving side before the
 * step, when the umpire recorded it.
 */
@Serializable
data class ReplayPoint(
    val index: Int,
    val kind: String,
    val wonBy: String,
    val server: String? = null,
    val atMs: Long,
    /** 1-based set the step belongs to. */
    val setNumber: Int,
    /** Played inside a tiebreak. */
    val tiebreak: Boolean = false,
    /** The receiving side had a break point going into this point. */
    val breakPoint: Boolean = false,
    /** This step won a game (a tiebreak counts as one). */
    val gameWon: Boolean = false,
    /** The game was won by the side that was receiving. */
    val breakOfServe: Boolean = false,
    /** This step won a set. */
    val setWon: Boolean = false,
    /** For an END step: COMPLETED, RETIRED or DEFAULTED. */
    val outcome: String? = null,
    val score: ReplayScore,
    /** Public code of the umpire who recorded this step (v2, #1183); absent when not known or not shown. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
    val scorer: String? = null,
)

/** The score at a step: sets already completed, games in the current set, and the point (or tiebreak) score. */
@Serializable
data class ReplayScore(
    val sets: List<ReplaySet>,
    val gamesTeam1: Int,
    val gamesTeam2: Int,
    /** "0", "15", "30", "40", "AD" — or the tiebreak count inside a tiebreak. */
    val pointsTeam1: String,
    val pointsTeam2: String,
)

/** A set's games, and its tiebreak points when one was played. */
@Serializable
data class ReplaySet(
    val team1: Int,
    val team2: Int,
    val tiebreakTeam1: Int? = null,
    val tiebreakTeam2: Int? = null,
)

/** The recorded-form result the replay ends at: the sets as `match_sets` stores them, and the winning side. */
@Serializable
data class ReplayResult(
    val sets: List<ReplaySet>,
    val winner: String,
)

/**
 * What `GET /api/v1/matches/code/{code}/replay` answers (#1183): the stored [ReplayDocument], filtered for
 * the caller.
 *
 * For a caller entitled to per-point scorers (an administrator, or whoever may organize the match's
 * event) the per-step `scorer` codes stay and [scorers] resolves each one. For everyone else both are
 * **omitted from the JSON** — not sent as null — so an anonymous response never carries them. Same format
 * either way: a client gates on the data being present, never on who it thinks it is.
 */
@Serializable
data class ReplayResponse(
    val version: Int,
    val result: ReplayResult,
    val events: List<ReplayEvent>,
    val points: List<ReplayPoint>,
    /** Public code → the scorer as shown today. Present only for an entitled caller. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
    val scorers: Map<String, ReplayScorer>? = null,
)

/**
 * A scorer, resolved at read time by the same display rules as a player: a deleted account shows as
 * deleted and never by name, so [displayName] is null whenever [isDeleted] is true.
 */
@Serializable
data class ReplayScorer(
    val displayName: String? = null,
    val isPlaceholder: Boolean = false,
    val isDeleted: Boolean = false,
)
