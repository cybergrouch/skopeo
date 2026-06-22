// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.calculator

import org.skopeo.dto.RankingCalculationRequest

/**
 * Interface for calculating tennis ranking updates based on match results.
 *
 * Different implementations can provide various algorithms for rating calculations
 * (e.g., standard Elo, performance-based with margin adjustments, etc.).
 */
interface RankingCalculator {
    /**
     * Calculate updated rankings based on match results.
     * Returns both the calculation result and an audit trail.
     *
     * This is a pure function with no side effects - it returns both the calculation
     * result and an audit trail that can be logged by the caller.
     */
    fun calculate(request: RankingCalculationRequest): RankingCalculationResult
}
