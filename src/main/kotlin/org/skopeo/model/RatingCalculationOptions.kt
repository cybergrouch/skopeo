// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import kotlinx.serialization.Serializable

/**
 * Configuration options for rating calculations.
 *
 * These options allow customization of the rating algorithm behavior,
 * particularly for implementing USTA NTRP Dynamic-style rating smoothing.
 */
@Serializable
data class RatingCalculationOptions(
    /**
     * Enable rating smoothing (USTA NTRP Dynamic style).
     *
     * When enabled, the final rating is calculated as an average between
     * the calculated new rating and the previous rating:
     *
     * ```
     * calculatedRating = previousRating + change
     * finalRating = (calculatedRating × smoothingFactor) + (previousRating × (1 - smoothingFactor))
     * ```
     *
     * This creates more stable ratings that converge gradually over multiple matches.
     *
     * Default: false (no smoothing, direct application of changes)
     */
    val smoothingEnabled: Boolean = false,
    /**
     * Smoothing factor (0.0 to 1.0).
     *
     * Determines how much weight to give the calculated rating vs the previous rating:
     * - 0.0: No change (rating never moves)
     * - 0.5: USTA NTRP Dynamic style (average of calculated and previous)
     * - 1.0: Full change (no smoothing, same as smoothingEnabled=false)
     *
     * Common values:
     * - 0.5: Standard USTA averaging
     * - 0.3: Conservative (30% of calculated change)
     * - 0.7: Aggressive (70% of calculated change)
     *
     * Default: 0.5 (USTA-style averaging)
     *
     * @throws IllegalArgumentException if not in range [0.0, 1.0]
     */
    val smoothingFactor: Double = 0.5,
) {
    init {
        require(smoothingFactor in 0.0..1.0) {
            "Smoothing factor must be between 0.0 and 1.0, got $smoothingFactor"
        }
    }
}
