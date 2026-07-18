// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.pow

private const val CONFIDENCE_SCALE = 6

// Sparsity + match-weight confidence tunables (#459). Kept as named vals so they stay centralized.
private const val WINDOW_DAYS = 30.0
private const val TARGET_MIDPOINT_GAP = 35.0
private const val DECAY_SHAPE = 2.5
private const val W_TOURNAMENT = 3.0
private const val W_LEAGUE = 1.5
private const val W_OPEN_PLAY = 0.5

/**
 * Rating confidence (#459, revising #343): how much to trust a player's current rating, from the
 * **sparsity** (density) of their meaningful matches over a fixed 30-day window. Higher-stakes matches
 * tell us more about true skill, so each class is weighted before summing:
 *
 * ```
 * weightedCount = 3.0·tournaments + 1.5·leagues + 0.5·openPlays   // over the last 30 days
 * if (weightedCount <= 0) confidence = 0                          // no qualifying play → 0%
 * averageGap = 30 / weightedCount                                 // days per weighted match
 * confidence = 1 / (1 + (averageGap / 35)^2.5)                    // log-logistic, midpoint 35d
 * ```
 *
 * Denser / higher-weight play shrinks the average gap and lifts confidence toward 1; light or absent
 * play widens the gap toward — and past — the 35-day midpoint, dropping confidence toward 0. Result in
 * [0, 1] at 6 dp; the UI shows a percentage. Computed on read from windowed match counts, never stored.
 *
 * **Density, not true clustering (#459):** `window / weightedCount` measures matches-per-window, so two
 * players with the same weighted count but different spacing score identically — model A in the spec
 * (`docs/product/RATING_CONFIDENCE_SPARSITY.md`). The former days-since-last-match decay and the
 * post-reset ramp (`min(1, matchesSinceReset/5)`) are intentionally dropped: a low weighted count
 * already yields low confidence.
 *
 * @param counts the player's COMPLETED matches in the window, split by weight class (#459).
 */
fun confidenceAt(counts: WeightClassCounts): BigDecimal {
    val weightedCount = W_TOURNAMENT * counts.tournaments + W_LEAGUE * counts.leagues + W_OPEN_PLAY * counts.openPlays
    if (weightedCount <= 0.0) return BigDecimal.ZERO.setScale(CONFIDENCE_SCALE)
    val averageGap = WINDOW_DAYS / weightedCount
    val confidence = 1.0 / (1.0 + (averageGap / TARGET_MIDPOINT_GAP).pow(x = DECAY_SHAPE))
    return BigDecimal.valueOf(confidence).setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP)
}
