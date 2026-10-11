// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.entity.livematch

import org.skopeo.domain.model.LoggedAction
import org.skopeo.domain.model.ScoreEvent
import org.skopeo.domain.model.TeamSide
import org.skopeo.repository.persistence.LiveMatchEventEntity

/**
 * `live_match_events` rows ↔ the domain's [LoggedAction] (#911).
 *
 * The repository deals in the database's strings and the engine deals in a sealed hierarchy; this is the
 * one place the two meet, which is what keeps the repository pure data access under
 * `LayeredArchitectureTest`.
 *
 * The string constants are duplicated in `chk_live_match_events_kind` (V55) on purpose — the database
 * refuses a kind it does not know, so a mapper that invented one could not persist it. [kindOf] and
 * [toLoggedAction] are exhaustive over the same set in opposite directions.
 *
 * **Only [kindOf] and [sideOf] are checked by the compiler.** They are `when`s over the sealed
 * hierarchy; [toLoggedAction]'s is a `when` over strings, `ScoreEventParser` holds its kinds in plain
 * sets, and the CHECK constraints are not Kotlin at all. A new kind therefore has to be added in four
 * places and the build only insists on two of them — which is how `SET_STARTED` shipped half-wired in
 * #988, and the checklist `GAME_STARTED` followed in #1083.
 *
 * `LiveMatchEventKindContractTest` (#989) is what closes that: it enumerates the sealed hierarchy and
 * drives every subtype request → event → row → event against a real database, so forgetting any one of
 * the four fails there.
 */
object LiveMatchEventKinds {
    const val POINT_WON = "POINT_WON"
    const val GAME_AWARDED = "GAME_AWARDED"
    const val SET_AWARDED = "SET_AWARDED"
    const val SET_STARTED = "SET_STARTED"
    const val GAME_STARTED = "GAME_STARTED"
    const val TIEBREAK_STARTED = "TIEBREAK_STARTED"
    const val SERVER_ASSIGNED = "SERVER_ASSIGNED"
    const val RETIRED = "RETIRED"
    const val DEFAULTED = "DEFAULTED"
    const val MATCH_AWARDED = "MATCH_AWARDED"
    const val UNDONE = "UNDONE"
    const val MATCH_STARTED = "MATCH_STARTED"
    const val PAUSED = "PAUSED"
    const val RESUMED = "RESUMED"
}

/** The stored `kind` for [event]. */
fun kindOf(event: ScoreEvent): String =
    when (event) {
        is ScoreEvent.PointWon -> LiveMatchEventKinds.POINT_WON
        is ScoreEvent.GameAwarded -> LiveMatchEventKinds.GAME_AWARDED
        is ScoreEvent.SetAwarded -> LiveMatchEventKinds.SET_AWARDED
        is ScoreEvent.SetStarted -> LiveMatchEventKinds.SET_STARTED
        is ScoreEvent.GameStarted -> LiveMatchEventKinds.GAME_STARTED
        is ScoreEvent.TiebreakStarted -> LiveMatchEventKinds.TIEBREAK_STARTED
        is ScoreEvent.ServerAssigned -> LiveMatchEventKinds.SERVER_ASSIGNED
        is ScoreEvent.Retired -> LiveMatchEventKinds.RETIRED
        is ScoreEvent.Defaulted -> LiveMatchEventKinds.DEFAULTED
        is ScoreEvent.MatchAwarded -> LiveMatchEventKinds.MATCH_AWARDED
        is ScoreEvent.MatchStarted -> LiveMatchEventKinds.MATCH_STARTED
        is ScoreEvent.Paused -> LiveMatchEventKinds.PAUSED
        is ScoreEvent.Resumed -> LiveMatchEventKinds.RESUMED
    }

/** The stored `side` for [event], or null for the kinds that name no side. */
fun sideOf(event: ScoreEvent): String? =
    when (event) {
        is ScoreEvent.PointWon -> event.side.name
        is ScoreEvent.GameAwarded -> event.side.name
        is ScoreEvent.SetAwarded -> event.side.name
        is ScoreEvent.Retired -> event.side.name
        is ScoreEvent.Defaulted -> event.side.name
        is ScoreEvent.MatchAwarded -> event.side.name
        // A side since #1098, so it stores like any other sided kind rather than in `player_id`.
        is ScoreEvent.ServerAssigned -> event.side.name
        is ScoreEvent.SetStarted,
        is ScoreEvent.GameStarted,
        is ScoreEvent.TiebreakStarted,
        is ScoreEvent.MatchStarted,
        is ScoreEvent.Paused,
        is ScoreEvent.Resumed,
        -> null
    }

/**
 * One stored row as the domain sees it.
 *
 * Throws on a row the domain cannot represent. That is not defensive coding for an expected case:
 * `chk_live_match_events_kind` and `chk_live_match_events_payload` make every one of these unreachable
 * for any row this application wrote, so reaching them means the table was written by something else —
 * which should be loud rather than silently folded into a wrong score.
 */
fun LiveMatchEventEntity.toLoggedAction(): LoggedAction =
    when (kind) {
        LiveMatchEventKinds.UNDONE ->
            LoggedAction.Undone(
                sequence = sequence,
                targetSequence = requireNotNull(value = targetSequence) { payloadError(field = "target_sequence") },
            )
        else -> LoggedAction.Scored(sequence = sequence, event = toScoreEvent())
    }

private fun LiveMatchEventEntity.toScoreEvent(): ScoreEvent = scoreEventOf(kind = kind, side = side, sequence = sequence)

/**
 * The [ScoreEvent] a stored [kind] and [side] describe — the inverse of [kindOf]. Shared by the live log
 * and the match replay's stored events (#1145), so there is still ONE mapping from a stored kind to an
 * event. [sequence] only labels an error.
 */
fun scoreEventOf(
    kind: String,
    side: String?,
    sequence: Long,
): ScoreEvent =
    when (kind) {
        LiveMatchEventKinds.SET_STARTED -> ScoreEvent.SetStarted
        LiveMatchEventKinds.GAME_STARTED -> ScoreEvent.GameStarted
        LiveMatchEventKinds.TIEBREAK_STARTED -> ScoreEvent.TiebreakStarted
        LiveMatchEventKinds.MATCH_STARTED -> ScoreEvent.MatchStarted
        LiveMatchEventKinds.PAUSED -> ScoreEvent.Paused
        LiveMatchEventKinds.RESUMED -> ScoreEvent.Resumed
        else -> sidedEventOf(kind = kind, sequence = sequence)(sideNamed(kind = kind, side = side, sequence = sequence))
    }

/**
 * The constructor for a kind that names a side. Split from [scoreEventOf] to keep each `when` within
 * detekt's complexity limit; the kind is checked before the side, so an unknown kind reports as unknown.
 */
private fun sidedEventOf(
    kind: String,
    sequence: Long,
): (TeamSide) -> ScoreEvent =
    when (kind) {
        LiveMatchEventKinds.POINT_WON -> { side -> ScoreEvent.PointWon(side = side) }
        LiveMatchEventKinds.GAME_AWARDED -> { side -> ScoreEvent.GameAwarded(side = side) }
        LiveMatchEventKinds.SET_AWARDED -> { side -> ScoreEvent.SetAwarded(side = side) }
        LiveMatchEventKinds.SERVER_ASSIGNED -> { side -> ScoreEvent.ServerAssigned(side = side) }
        LiveMatchEventKinds.RETIRED -> { side -> ScoreEvent.Retired(side = side) }
        LiveMatchEventKinds.DEFAULTED -> { side -> ScoreEvent.Defaulted(side = side) }
        LiveMatchEventKinds.MATCH_AWARDED -> { side -> ScoreEvent.MatchAwarded(side = side) }
        else -> error(message = "Unknown live-match event kind '$kind' at sequence $sequence. $CHECK_HINT")
    }

private fun sideNamed(
    kind: String,
    side: String?,
    sequence: Long,
): TeamSide =
    TeamSide.entries.firstOrNull { it.name == side }
        ?: error(message = "Live-match event '$kind' at sequence $sequence has side '$side'. $CHECK_HINT")

private fun LiveMatchEventEntity.payloadError(field: String): String =
    "Live-match event '$kind' at sequence $sequence has no $field. $CHECK_HINT"

private const val CHECK_HINT =
    "chk_live_match_events_kind and chk_live_match_events_payload (V55) make this unreachable for rows " +
        "this application wrote, so the table has been written by something else."
