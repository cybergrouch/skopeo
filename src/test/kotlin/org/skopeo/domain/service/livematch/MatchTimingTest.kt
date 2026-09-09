// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.skopeo.domain.mapper.entity.livematch.LiveMatchEventKinds
import org.skopeo.repository.persistence.LiveMatchEventEntity
import java.time.LocalDateTime
import java.util.UUID

/**
 * Elapsed playing time (#937). A pure fold over the log's timestamps, so `now` is supplied rather than
 * read — otherwise every one of these would depend on wall time.
 */
class MatchTimingTest {
    private val base: LocalDateTime = LocalDateTime.of(2026, 3, 1, 10, 0, 0)
    private val umpire: UUID = UUID.randomUUID()

    private var sequence = 0L

    private fun row(
        kind: String,
        atSeconds: Long,
    ) = LiveMatchEventEntity(
        sequence = ++sequence,
        kind = kind,
        // The sided kinds, matching chk_live_match_events_payload — the mapper refuses a row that omits
        // a side it requires, which is how this fixture got caught building a sideless RETIRED.
        side = if (kind in SIDED) "TEAM1" else null,
        playerId = null,
        targetSequence = null,
        recordedBy = umpire,
        recordedAt = base.plusSeconds(atSeconds),
    )

    private fun undoOf(target: Long) =
        LiveMatchEventEntity(
            sequence = ++sequence,
            kind = LiveMatchEventKinds.UNDONE,
            side = null,
            playerId = null,
            targetSequence = target,
            recordedBy = umpire,
            recordedAt = base.plusSeconds(9_999),
        )

    private fun at(seconds: Long) = base.plusSeconds(seconds)

    private companion object {
        val SIDED =
            setOf(
                LiveMatchEventKinds.POINT_WON,
                LiveMatchEventKinds.GAME_AWARDED,
                LiveMatchEventKinds.SET_AWARDED,
                LiveMatchEventKinds.RETIRED,
                LiveMatchEventKinds.DEFAULTED,
                LiveMatchEventKinds.MATCH_AWARDED,
            )
    }

    @Test
    fun `a match that has not started reads zero and is not running`() {
        // Deliberately not "since the first thing that happened": the gap between opening the app and
        // the players starting is exactly what MATCH_STARTED exists to exclude (#930).
        val rows = listOf(element = row(kind = LiveMatchEventKinds.POINT_WON, atSeconds = 0))
        matchTiming(rows = rows, now = at(seconds = 600)) shouldBe
            MatchTiming(elapsedSeconds = 0, isRunning = false)
    }

    @Test
    fun `an empty log reads zero`() {
        matchTiming(rows = emptyList(), now = at(seconds = 600)) shouldBe
            MatchTiming(elapsedSeconds = 0, isRunning = false)
    }

    @Test
    fun `a running match counts from the start to now`() {
        val rows = listOf(element = row(kind = LiveMatchEventKinds.MATCH_STARTED, atSeconds = 0))
        matchTiming(rows = rows, now = at(seconds = 600)) shouldBe
            MatchTiming(elapsedSeconds = 600, isRunning = true)
    }

    @Test
    fun `a suspension is excluded, which is the whole point`() {
        // Played 0-100, paused 100-400, resumed at 400, now 500. Playing time is 100 + 100 = 200, not the
        // 500 seconds of wall clock.
        val rows =
            listOf(
                row(kind = LiveMatchEventKinds.MATCH_STARTED, atSeconds = 0),
                row(kind = LiveMatchEventKinds.PAUSED, atSeconds = 100),
                row(kind = LiveMatchEventKinds.RESUMED, atSeconds = 400),
            )
        matchTiming(rows = rows, now = at(seconds = 500)) shouldBe
            MatchTiming(elapsedSeconds = 200, isRunning = true)
    }

    @Test
    fun `while paused the clock is frozen and reports itself as stopped`() {
        val rows =
            listOf(
                row(kind = LiveMatchEventKinds.MATCH_STARTED, atSeconds = 0),
                row(kind = LiveMatchEventKinds.PAUSED, atSeconds = 100),
            )
        // A rain delay may last days; none of it is playing time, and the client must stop ticking.
        matchTiming(rows = rows, now = at(seconds = 100_000)) shouldBe
            MatchTiming(elapsedSeconds = 100, isRunning = false)
    }

    @Test
    fun `several suspensions all come off`() {
        val rows =
            listOf(
                row(kind = LiveMatchEventKinds.MATCH_STARTED, atSeconds = 0),
                row(kind = LiveMatchEventKinds.PAUSED, atSeconds = 60),
                row(kind = LiveMatchEventKinds.RESUMED, atSeconds = 160),
                row(kind = LiveMatchEventKinds.PAUSED, atSeconds = 220),
                row(kind = LiveMatchEventKinds.RESUMED, atSeconds = 520),
            )
        // 60 + 60 + 80 played; 100 + 300 suspended.
        matchTiming(rows = rows, now = at(seconds = 600)) shouldBe
            MatchTiming(elapsedSeconds = 200, isRunning = true)
    }

    @Test
    fun `restarting after a suspension resumes the clock without a RESUMED`() {
        // Mirrors the engine, where MATCH_STARTED also clears the paused flag.
        val rows =
            listOf(
                row(kind = LiveMatchEventKinds.MATCH_STARTED, atSeconds = 0),
                row(kind = LiveMatchEventKinds.PAUSED, atSeconds = 100),
                row(kind = LiveMatchEventKinds.MATCH_STARTED, atSeconds = 400),
            )
        matchTiming(rows = rows, now = at(seconds = 500)) shouldBe
            MatchTiming(elapsedSeconds = 200, isRunning = true)
    }

    @Test
    fun `the clock freezes when the match ends, not when it is finalized`() {
        // A finished match must not keep accruing minutes because nobody has finalized it yet.
        val rows =
            listOf(
                row(kind = LiveMatchEventKinds.MATCH_STARTED, atSeconds = 0),
                row(kind = LiveMatchEventKinds.MATCH_AWARDED, atSeconds = 300),
            )
        matchTiming(rows = rows, now = at(seconds = 100_000)) shouldBe
            MatchTiming(elapsedSeconds = 300, isRunning = false)
    }

    @Test
    fun `a retirement stops the clock too`() {
        val rows =
            listOf(
                row(kind = LiveMatchEventKinds.MATCH_STARTED, atSeconds = 0),
                row(kind = LiveMatchEventKinds.RETIRED, atSeconds = 250),
            )
        matchTiming(rows = rows, now = at(seconds = 9_000)) shouldBe
            MatchTiming(elapsedSeconds = 250, isRunning = false)
    }

    @Test
    fun `an undone pause did not happen, so the time is not subtracted`() {
        // The reason timing respects undo: a mis-tapped pause would otherwise silently remove real
        // playing time that was never actually suspended.
        val started = row(kind = LiveMatchEventKinds.MATCH_STARTED, atSeconds = 0)
        val paused = row(kind = LiveMatchEventKinds.PAUSED, atSeconds = 100)
        val rows = listOf(started, paused, undoOf(target = paused.sequence))

        matchTiming(rows = rows, now = at(seconds = 500)) shouldBe
            MatchTiming(elapsedSeconds = 500, isRunning = true)
    }

    @Test
    fun `an undone start means the match has not started`() {
        val started = row(kind = LiveMatchEventKinds.MATCH_STARTED, atSeconds = 0)
        val rows = listOf(started, undoOf(target = started.sequence))

        matchTiming(rows = rows, now = at(seconds = 500)) shouldBe
            MatchTiming(elapsedSeconds = 0, isRunning = false)
    }

    @Test
    fun `a clock skew that puts a row ahead of now reads zero, never negative`() {
        // Two Cloud Run instances, two clocks. A countdown would be worse than a stuck zero.
        val rows = listOf(element = row(kind = LiveMatchEventKinds.MATCH_STARTED, atSeconds = 60))
        matchTiming(rows = rows, now = at(seconds = 0)) shouldBe
            MatchTiming(elapsedSeconds = 0, isRunning = true)
    }
}
