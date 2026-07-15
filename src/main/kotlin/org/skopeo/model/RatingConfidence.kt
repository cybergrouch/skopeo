// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

private const val CONFIDENCE_SCALE = 6
private const val DECAY_HALF_POINT_DAYS = 35.0
private const val DECAY_SHAPE = 2.5
private const val RAMP_MATCHES = 5.0

/**
 * Rating confidence (#343): how much to trust a player's current rating, as a smooth **log-logistic**
 * time decay tempered by how many matches have accrued since the rating was last reset:
 *
 * ```
 * decay = 1 / (1 + (days / 35)^2.5)        // days since the last match-calc rating
 * scale = min(1, m / 5)                    // m = matches since the last override / NTRP band jump
 * confidence = decay * scale
 * ```
 *
 * The time decay is 1.0 the day a match calculation sets the rating, ≈0.5 around 35 days later, and
 * trails toward 0. The scale ramps confidence from ~0 up over the first ~5 matches after a **reset** —
 * an admin/RATER override, a sign-up self-rating, or an NTRP **band jump** (all treated alike). A rating
 * that isn't match-derived has no [matchRatedAt] and is **0**. Result in [0, 1] at 6 dp; the UI shows a
 * percentage. Computed on read, never stored.
 *
 * @param matchesSinceReset matches applied since the last reset (override/self-rating/band jump).
 */
fun confidenceAt(
    matchRatedAt: LocalDateTime?,
    matchesSinceReset: Int,
    now: LocalDateTime,
): BigDecimal {
    if (matchRatedAt == null) return BigDecimal.ZERO.setScale(CONFIDENCE_SCALE)
    val days = ChronoUnit.DAYS.between(matchRatedAt, now).coerceAtLeast(minimumValue = 0L).toDouble()
    val decay = 1.0 / (1.0 + Math.pow(days / DECAY_HALF_POINT_DAYS, DECAY_SHAPE))
    val scale = (matchesSinceReset.coerceAtLeast(minimumValue = 0) / RAMP_MATCHES).coerceAtMost(maximumValue = 1.0)
    return BigDecimal.valueOf(decay * scale).setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP)
}
