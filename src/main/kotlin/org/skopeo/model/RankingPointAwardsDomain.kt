// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

// The Points Management ledger read-path result aggregate (#472), produced by RankingPointService and
// mapped to the API response by the mapper layer. Kept in model so the dto/mapper boundary never reaches
// into the service package.

/**
 * One ledger row enriched for the Points Management list (#472): the raw [award] plus the player's
 * display name + public code and the granting source's public code (match, else event; null for a
 * manual / external grant). Names + codes are resolved once per page (batched), not per row.
 */
data class ResolvedAward(
    val award: RankingPointAward,
    val playerDisplayName: String?,
    val playerPublicCode: String?,
    val matchPublicCode: String?,
    val eventPublicCode: String?,
    // True for a login-less, not-yet-claimed placeholder ("dummy") player (#496/#505), resolved from
    // the batched user lookup — the ledger renders an "Unclaimed" tag beside the name.
    val playerIsPlaceholder: Boolean = false,
    // True for an admin-soft-deleted account (#518), resolved from the same batched user lookup — the
    // ledger renders a dominant "Deleted" chip.
    val playerIsDeleted: Boolean = false,
)

/** One page of the whole ledger (#472): the resolved rows plus the full total for the pager. */
data class AwardsPage(
    val rows: List<ResolvedAward>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)
