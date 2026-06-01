package org.lange.tennis.levelr.service

import org.lange.tennis.levelr.dto.RankingCalculationResponse

/**
 * Result of a ranking calculation containing both the response and audit trail.
 * This allows the calculation to be pure (no side effects) while still providing
 * detailed logging information that can be processed by the caller.
 */
data class RankingCalculationResult(
    val response: RankingCalculationResponse,
    val audit: List<AuditEntry>,
)
