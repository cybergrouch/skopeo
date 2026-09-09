// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import org.skopeo.common.dto.livematch.LiveMatchResponse

/**
 * Push the current score to spectators (#911 §3, §6).
 *
 * **What is broadcast is state, never keystrokes.** Every umpire action — including an undo — follows
 * the same path: append to the log, fold it, write the resulting score. An undo reaches a spectator as
 * *the score changed back*, not as an operation they have to interpret. Three things follow, all in our
 * favour: reconnect is trivial (read the current document, no gap to fill), a stale client can tell it
 * is behind from [LiveScorePayload.sequence], and the append-only log never has to be public.
 *
 * **Broadcasting must never fail the umpire's write.** The log is the system of record and the broadcast
 * is a projection of it; a spectator seeing a stale score is a far smaller problem than a point that did
 * not get recorded because a network call to Firestore timed out. Implementations therefore swallow their
 * own failures — see [FirestoreLiveScoreBroadcaster].
 */
fun interface LiveScoreBroadcaster {
    /** Publish [payload]. Best-effort by contract: implementations must not throw. */
    fun publish(payload: LiveScorePayload)
}

/**
 * The spectator-facing document: one per live match, overwritten on every change.
 *
 * Deliberately a **projection of [LiveMatchResponse] minus the umpire's business** — no log, no scorer
 * id. A spectator needs the scoreboard; who is holding the phone and how they corrected themselves are
 * internal. Keeping the public surface to one small document is also what makes reconnect a plain read.
 */
data class LiveScorePayload(
    /**
     * The match's **public** code, not its internal id.
     *
     * This document is world-readable, and the internal id is deliberately withheld from ordinary
     * viewers (#776/#911) precisely so it is not an alternative public identifier. Keying and carrying
     * the public code keeps that true — and it is also the only identifier a spectator *has*, since the
     * page they arrived from is addressed by code.
     */
    val publicCode: String,
    val sequence: Long,
    val pointsTeam1: String,
    val pointsTeam2: String,
    val gamesTeam1: Int,
    val gamesTeam2: Int,
    val sets: List<LiveScorePayloadSet>,
    val isTiebreak: Boolean,
    val isPaused: Boolean,
    val hasStarted: Boolean,
    val serverId: String?,
    val outcomeKind: String?,
    val outcomeWinner: String?,
) {
    /** Firestore takes a plain map; nulls are kept so a field that *became* null is cleared on overwrite. */
    fun asDocument(): Map<String, Any?> =
        mapOf(
            "publicCode" to publicCode,
            "sequence" to sequence,
            "pointsTeam1" to pointsTeam1,
            "pointsTeam2" to pointsTeam2,
            "gamesTeam1" to gamesTeam1,
            "gamesTeam2" to gamesTeam2,
            "sets" to sets.map { it.asDocument() },
            "isTiebreak" to isTiebreak,
            "isPaused" to isPaused,
            "hasStarted" to hasStarted,
            "serverId" to serverId,
            "outcomeKind" to outcomeKind,
            "outcomeWinner" to outcomeWinner,
        )
}

/** A banked set, as a spectator sees it. */
data class LiveScorePayloadSet(
    val gamesTeam1: Int,
    val gamesTeam2: Int,
    val tiebreakTeam1Points: Int?,
    val tiebreakTeam2Points: Int?,
) {
    fun asDocument(): Map<String, Any?> =
        mapOf(
            "gamesTeam1" to gamesTeam1,
            "gamesTeam2" to gamesTeam2,
            "tiebreakTeam1Points" to tiebreakTeam1Points,
            "tiebreakTeam2Points" to tiebreakTeam2Points,
        )
}

/**
 * The projection, as a pure function so it can be tested without Firestore or a network.
 *
 * Note `scorerId` is dropped on purpose: it is the umpire's view, not the spectators'. So is the
 * internal `matchId`: [publicCode] is passed in instead, because this ends up on a world-readable
 * document and the internal id is not a public identifier.
 */
fun LiveMatchResponse.toBroadcast(publicCode: String): LiveScorePayload =
    LiveScorePayload(
        publicCode = publicCode,
        sequence = sequence,
        pointsTeam1 = pointsTeam1,
        pointsTeam2 = pointsTeam2,
        gamesTeam1 = gamesTeam1,
        gamesTeam2 = gamesTeam2,
        sets =
            sets.map {
                LiveScorePayloadSet(
                    gamesTeam1 = it.gamesTeam1,
                    gamesTeam2 = it.gamesTeam2,
                    tiebreakTeam1Points = it.tiebreakTeam1Points,
                    tiebreakTeam2Points = it.tiebreakTeam2Points,
                )
            },
        isTiebreak = isTiebreak,
        isPaused = isPaused,
        hasStarted = hasStarted,
        serverId = serverId,
        outcomeKind = outcome?.kind,
        outcomeWinner = outcome?.winner,
    )

/**
 * The broadcaster used when Firestore is not configured — every environment except a deployed one.
 *
 * Not a stub for tests to swap in: it is the **default**, because local development and CI have no
 * Firestore project and must not need one. Live scoring works fully without a broadcast; spectators
 * simply have nothing to watch.
 */
object NoOpLiveScoreBroadcaster : LiveScoreBroadcaster {
    override fun publish(payload: LiveScorePayload) = Unit
}
