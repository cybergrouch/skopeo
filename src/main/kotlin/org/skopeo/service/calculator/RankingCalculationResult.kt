// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.calculator

import org.skopeo.dto.RankingCalculationResponse

/**
 * Result of a ranking calculation containing both the response and audit trail.
 * This allows the calculation to be pure (no side effects) while still providing
 * detailed logging information that can be processed by the caller.
 */
data class RankingCalculationResult(
    val response: RankingCalculationResponse,
    val audit: List<AuditEntry>,
)
