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
 * [toLoggedAction] are exhaustive over the same set in opposite directions, and `LiveMatchMapperTest`
 * round-trips every variant so the two cannot drift.
 */
object LiveMatchEventKinds {
    const val POINT_WON = "POINT_WON"
    const val GAME_AWARDED = "GAME_AWARDED"
    const val SET_AWARDED = "SET_AWARDED"
    const val TIEBREAK_STARTED = "TIEBREAK_STARTED"
    const val SERVER_ASSIGNED = "SERVER_ASSIGNED"
    const val RETIRED = "RETIRED"
    const val DEFAULTED = "DEFAULTED"
    const val MATCH_AWARDED = "MATCH_AWARDED"
    const val UNDONE = "UNDONE"
}

/** The stored `kind` for [event]. */
fun kindOf(event: ScoreEvent): String =
    when (event) {
        is ScoreEvent.PointWon -> LiveMatchEventKinds.POINT_WON
        is ScoreEvent.GameAwarded -> LiveMatchEventKinds.GAME_AWARDED
        is ScoreEvent.SetAwarded -> LiveMatchEventKinds.SET_AWARDED
        is ScoreEvent.TiebreakStarted -> LiveMatchEventKinds.TIEBREAK_STARTED
        is ScoreEvent.ServerAssigned -> LiveMatchEventKinds.SERVER_ASSIGNED
        is ScoreEvent.Retired -> LiveMatchEventKinds.RETIRED
        is ScoreEvent.Defaulted -> LiveMatchEventKinds.DEFAULTED
        is ScoreEvent.MatchAwarded -> LiveMatchEventKinds.MATCH_AWARDED
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
        is ScoreEvent.TiebreakStarted, is ScoreEvent.ServerAssigned -> null
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

private fun LiveMatchEventEntity.toScoreEvent(): ScoreEvent =
    when (kind) {
        LiveMatchEventKinds.POINT_WON -> ScoreEvent.PointWon(side = requiredSide())
        LiveMatchEventKinds.GAME_AWARDED -> ScoreEvent.GameAwarded(side = requiredSide())
        LiveMatchEventKinds.SET_AWARDED -> ScoreEvent.SetAwarded(side = requiredSide())
        LiveMatchEventKinds.TIEBREAK_STARTED -> ScoreEvent.TiebreakStarted
        LiveMatchEventKinds.SERVER_ASSIGNED ->
            ScoreEvent.ServerAssigned(playerId = requireNotNull(value = playerId) { payloadError(field = "player_id") })
        LiveMatchEventKinds.RETIRED -> ScoreEvent.Retired(side = requiredSide())
        LiveMatchEventKinds.DEFAULTED -> ScoreEvent.Defaulted(side = requiredSide())
        LiveMatchEventKinds.MATCH_AWARDED -> ScoreEvent.MatchAwarded(side = requiredSide())
        else -> error(message = "Unknown live-match event kind '$kind' at sequence $sequence. ${CHECK_HINT}")
    }

private fun LiveMatchEventEntity.requiredSide(): TeamSide =
    TeamSide.entries.firstOrNull { it.name == side }
        ?: error(message = "Live-match event '$kind' at sequence $sequence has side '$side'. ${CHECK_HINT}")

private fun LiveMatchEventEntity.payloadError(field: String): String =
    "Live-match event '$kind' at sequence $sequence has no $field. $CHECK_HINT"

private const val CHECK_HINT =
    "chk_live_match_events_kind and chk_live_match_events_payload (V55) make this unreachable for rows " +
        "this application wrote, so the table has been written by something else."
