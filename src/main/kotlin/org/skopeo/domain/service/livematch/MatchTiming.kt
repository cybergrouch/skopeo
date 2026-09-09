// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.livematch

import org.skopeo.domain.mapper.entity.livematch.LiveMatchEventKinds
import org.skopeo.domain.mapper.entity.livematch.toLoggedAction
import org.skopeo.repository.persistence.LiveMatchEventEntity
import java.time.Duration
import java.time.LocalDateTime

/**
 * How long a match has actually been played (#937).
 *
 * @property elapsedSeconds playing time so far, **excluding** every suspension.
 * @property isRunning whether the clock is currently advancing — false before the start, while paused,
 *  and once the match has ended. A client ticks locally while this is true and stops when it is not.
 */
data class MatchTiming(
    val elapsedSeconds: Long,
    val isRunning: Boolean,
)

/**
 * Elapsed playing time, folded from the log's timestamps (#937).
 *
 * ```
 * (end − MATCH_STARTED) − Σ(RESUMED − PAUSED)
 * ```
 *
 * A **pure function taking [now]**, for the same reason the scoring engine is pure: a clock read inside
 * would make every test of this depend on wall time. The caller supplies the instant.
 *
 * Three things worth stating, because each is a decision rather than an obvious reading:
 *
 * - **It starts at `MATCH_STARTED`, not at the first point.** That is why `MATCH_STARTED` exists as its
 *   own event (#930): the gap between an umpire opening the app and the players actually starting is
 *   exactly what would corrupt the figure. With no `MATCH_STARTED` the answer is zero, not "since the
 *   first thing that happened".
 * - **It respects undo.** An undone `MATCH_STARTED` or `PAUSED` did not happen, so timing is computed
 *   over the *surviving* actions rather than the raw rows. Otherwise a mis-tapped pause would silently
 *   subtract real playing time that was never actually suspended.
 * - **It freezes when the match ends.** Once a surviving `RETIRED` / `DEFAULTED` / `MATCH_AWARDED`
 *   exists, the clock stops at that row's timestamp rather than running on to [now] — a finished match
 *   must not keep accruing minutes because nobody finalized it yet.
 */
fun matchTiming(
    rows: List<LiveMatchEventEntity>,
    now: LocalDateTime,
): MatchTiming {
    val surviving = survivingRows(rows = rows)
    val startedAt =
        surviving.firstOrNull { it.kind == LiveMatchEventKinds.MATCH_STARTED }?.recordedAt
            ?: return MatchTiming(elapsedSeconds = 0, isRunning = false)

    val endedAt = surviving.firstOrNull { it.kind in TERMINAL_KINDS }?.recordedAt
    val considered = surviving.filter { it.recordedAt >= startedAt && (endedAt == null || it.recordedAt <= endedAt) }

    var elapsed = Duration.ZERO
    var playingSince: LocalDateTime? = startedAt
    considered.forEach { row ->
        when (row.kind) {
            LiveMatchEventKinds.PAUSED ->
                playingSince?.let { since ->
                    elapsed += Duration.between(since, row.recordedAt)
                    playingSince = null
                }
            // MATCH_STARTED also resumes: a restart after a suspension needs no separate RESUMED, which
            // mirrors what the engine does to the paused flag.
            LiveMatchEventKinds.RESUMED, LiveMatchEventKinds.MATCH_STARTED ->
                if (playingSince == null) playingSince = row.recordedAt
            else -> Unit
        }
    }

    val closeAt = endedAt ?: now
    playingSince?.let { since -> elapsed += Duration.between(since, closeAt) }
    return MatchTiming(
        // Never negative, however odd the clocks: a row timestamped slightly ahead of `now` (two Cloud
        // Run instances, two clocks) must read as zero rather than as a countdown.
        elapsedSeconds = elapsed.seconds.coerceAtLeast(minimumValue = 0),
        isRunning = playingSince != null && endedAt == null,
    )
}

/** The rows still in force, in sequence order — undo resolved by the engine, not re-derived here. */
private fun survivingRows(rows: List<LiveMatchEventEntity>): List<LiveMatchEventEntity> {
    val live = ScoreEngine.surviving(log = rows.map { it.toLoggedAction() }).map { it.sequence }.toSet()
    return rows.filter { it.sequence in live }.sortedBy { it.sequence }
}

private val TERMINAL_KINDS =
    setOf(LiveMatchEventKinds.RETIRED, LiveMatchEventKinds.DEFAULTED, LiveMatchEventKinds.MATCH_AWARDED)
