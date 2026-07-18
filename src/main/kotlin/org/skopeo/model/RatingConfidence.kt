// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit.DAYS
import kotlin.math.pow

private const val CONFIDENCE_SCALE = 6

// 3-factor confidence tunables (#459). Kept as named consts so they stay centralized.
private const val WINDOW_DAYS = 30.0
private const val TARGET_MIDPOINT_GAP = 35.0
private const val DECAY_SHAPE = 2.5
private const val W_TOURNAMENT = 3.0
private const val W_LEAGUE = 1.5
private const val W_OPEN_PLAY = 0.5

/** The shared log-logistic decay f(x) = 1 / (1 + (x / 35)^2.5): 1.0 at x=0, ~0.5 at the 35-day midpoint. */
private fun decay(days: Double): Double = 1.0 / (1.0 + (days / TARGET_MIDPOINT_GAP).pow(x = DECAY_SHAPE))

/** The confidence weight this class contributes to the weighted density (higher stakes → more signal). */
private fun WeightClass.weight(): Double =
    when (this) {
        WeightClass.TOURNAMENT -> W_TOURNAMENT
        WeightClass.LEAGUE -> W_LEAGUE
        WeightClass.OPEN_PLAY -> W_OPEN_PLAY
    }

/**
 * Rating confidence (#459, revising #343 and the sparsity-only #461): how much to trust a player's
 * current rating, as a **3-factor multiplicative** model over their COMPLETED matches in a fixed 30-day
 * window. All three factors share the same log-logistic decay `f(x) = 1 / (1 + (x / 35)^2.5)`:
 *
 * ```
 * weightedCount = 3.0·tournaments + 1.5·leagues + 0.5·openPlays   // over the last 30 days
 * if (no matches in window) confidence = 0                        // no qualifying play → 0%
 * recency  = f(daysSinceLastMatch)     // freshness of the most recent match
 * sparsity = f(30 / weightedCount)     // weighted density: denser/higher-stakes play → smaller gap
 * spacing  = f(maxInternalGap)         // biggest hole BETWEEN consecutive matches; 1.0 with ≤ 1 match
 * confidence = recency · sparsity · spacing
 * ```
 *
 * - **Recency** rewards a fresh latest match and decays as the window's newest match ages.
 * - **Sparsity** rewards volume + match quality (a tournament closes the gap ~6× faster than open play).
 * - **Spacing** penalizes *internal* clustering — a burst-then-gap month has a big hole between matches.
 *   It looks only at gaps **between** matches, never the trailing gap to `now`: recency already covers
 *   that, so spacing avoids double-counting the recency/trailing gap. With ≤ 1 match there is no internal
 *   gap, so spacing = 1.0 and recency + sparsity carry the score.
 *
 * Result in [0, 1] at 6 dp; the UI shows a percentage. Computed on read from the windowed match rows,
 * never stored. See `docs/product/RATING_CONFIDENCE_SPARSITY.md`.
 *
 * @param matches the player's COMPLETED matches in the window as (date, weight class) rows (#459).
 * @param now the evaluation instant; recency is measured from the latest in-window match to this.
 */
fun confidenceAt(
    matches: List<WindowMatch>,
    now: LocalDateTime,
): BigDecimal {
    if (matches.isEmpty()) return BigDecimal.ZERO.setScale(CONFIDENCE_SCALE)

    val weightedCount = matches.sumOf { it.weightClass.weight() }
    val sortedDates = matches.map { it.matchDate }.sorted()
    val latest = sortedDates.last()

    val daysSinceLast = DAYS.between(latest, now.toLocalDate()).coerceAtLeast(minimumValue = 0L).toDouble()
    val maxInternalGap =
        sortedDates
            .zipWithNext { earlier, later -> DAYS.between(earlier, later).toDouble() }
            .maxOrNull() ?: 0.0

    val recency = decay(days = daysSinceLast)
    val sparsity = decay(days = WINDOW_DAYS / weightedCount)
    val spacing = decay(days = maxInternalGap)
    val confidence = recency * sparsity * spacing
    return BigDecimal.valueOf(confidence).setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP)
}
