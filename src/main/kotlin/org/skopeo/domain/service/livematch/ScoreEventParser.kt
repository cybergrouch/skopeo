// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import org.skopeo.common.dto.livematch.LiveScoreEventRequest
import org.skopeo.common.error.ServiceError
import org.skopeo.domain.mapper.entity.livematch.LiveMatchEventKinds
import org.skopeo.domain.model.ScoreEvent
import org.skopeo.domain.model.TeamSide
import java.util.UUID

/**
 * The wire's strings → a [ScoreEvent] (#911).
 *
 * Lives in `service` rather than in the route because `routes` may not depend on `model` — a rule
 * `LayeredArchitectureTest` enforces with no exception — so a route cannot name a `ScoreEvent` at all.
 * Split out of `LiveMatchService` because parsing is a separate concern from scoring, and because the
 * service was carrying more responsibilities than one class should.
 *
 * Two principles in the error messages:
 *
 * - **Name the permitted values.** A 400 that only says "invalid" costs the caller a round trip to the
 *   docs.
 * - **Reject a payload the kind does not use, rather than ignoring it.** Silently dropping a field the
 *   caller meant is how a scoreboard ends up quietly wrong.
 */
object ScoreEventParser {
    fun parse(request: LiveScoreEventRequest): Either<ServiceError, ScoreEvent> =
        either {
            val side = request.side?.let { raw -> TeamSide.entries.firstOrNull { it.name == raw.uppercase() } }
            ensure(condition = request.side == null || side != null) {
                ServiceError.Validation(message = "Unknown side '${request.side}'; expected TEAM1 or TEAM2")
            }
            when (val kind = request.kind.uppercase()) {
                in SIDED_KINDS -> sided(kind = kind, side = requireSide(side = side, kind = kind).bind())
                in BARE_KINDS -> bare(kind = kind)
                LiveMatchEventKinds.SERVER_ASSIGNED ->
                    ScoreEvent.ServerAssigned(playerId = requirePlayer(raw = request.playerId).bind())
                else ->
                    raise(
                        r =
                            ServiceError.Validation(
                                message = "Unknown event kind '${request.kind}'; expected one of ${RECORDABLE_KINDS.joinToString()}",
                            ),
                    )
            }
        }

    private fun sided(
        kind: String,
        side: TeamSide,
    ): ScoreEvent =
        when (kind) {
            LiveMatchEventKinds.POINT_WON -> ScoreEvent.PointWon(side = side)
            LiveMatchEventKinds.GAME_AWARDED -> ScoreEvent.GameAwarded(side = side)
            LiveMatchEventKinds.SET_AWARDED -> ScoreEvent.SetAwarded(side = side)
            LiveMatchEventKinds.RETIRED -> ScoreEvent.Retired(side = side)
            LiveMatchEventKinds.DEFAULTED -> ScoreEvent.Defaulted(side = side)
            else -> ScoreEvent.MatchAwarded(side = side)
        }

    private fun bare(kind: String): ScoreEvent =
        when (kind) {
            LiveMatchEventKinds.TIEBREAK_STARTED -> ScoreEvent.TiebreakStarted
            LiveMatchEventKinds.MATCH_STARTED -> ScoreEvent.MatchStarted
            LiveMatchEventKinds.PAUSED -> ScoreEvent.Paused
            else -> ScoreEvent.Resumed
        }

    private fun requireSide(
        side: TeamSide?,
        kind: String,
    ): Either<ServiceError, TeamSide> = side?.right() ?: ServiceError.Validation(message = "$kind requires a side (TEAM1 or TEAM2)").left()

    private fun requirePlayer(raw: String?): Either<ServiceError, UUID> {
        val id = raw ?: return ServiceError.Validation(message = "SERVER_ASSIGNED requires a playerId").left()
        return runCatching { UUID.fromString(id) }.getOrNull()?.right()
            ?: ServiceError.Validation(message = "playerId '$id' is not a valid id").left()
    }

    /** Kinds that name a side. For RETIRED/DEFAULTED the side is the one that CONCEDED. */
    private val SIDED_KINDS =
        setOf(
            LiveMatchEventKinds.POINT_WON,
            LiveMatchEventKinds.GAME_AWARDED,
            LiveMatchEventKinds.SET_AWARDED,
            LiveMatchEventKinds.RETIRED,
            LiveMatchEventKinds.DEFAULTED,
            LiveMatchEventKinds.MATCH_AWARDED,
        )

    /** Kinds that carry no payload at all. */
    private val BARE_KINDS =
        setOf(
            LiveMatchEventKinds.TIEBREAK_STARTED,
            LiveMatchEventKinds.MATCH_STARTED,
            LiveMatchEventKinds.PAUSED,
            LiveMatchEventKinds.RESUMED,
        )

    /**
     * What a caller may POST. `UNDONE` is absent on purpose: undo has its own endpoint, because the
     * server picks the target (the last surviving action) rather than trusting a client to name it.
     */
    private val RECORDABLE_KINDS = SIDED_KINDS + BARE_KINDS + LiveMatchEventKinds.SERVER_ASSIGNED
}
