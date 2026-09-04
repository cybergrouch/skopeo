// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.model

/**
 * Whether a ranking-point award counts toward its owner's standing **right now** (#403 decision #2).
 *
 * An award is tagged with the band its owner held when it was earned, and it counts only while that tag
 * still equals their current published level. Change bands and the award goes **latent**: still owned,
 * still in the ledger, simply not counting — and it resumes counting if they return to that band.
 *
 * **This is the one definition of that rule** (#882). It used to live only inside
 * `StandingsCalculationService`, while the profile's points audit listed every ACTIVE, in-window award
 * with no band filter at all. So a promoted player's profile showed points as live while the Ranking
 * section on the same page said "Unranked" — two surfaces asserting different things about the same
 * awards. Both now ask this function.
 *
 * Deliberately a free function over two strings rather than a method on [RankingPointAward]: standings
 * evaluates it on raw persistence rows in a hot loop, the profile on mapped domain objects, and neither
 * should have to convert just to ask the question.
 *
 * Nulls are false: an award with no band tag, or a player with no current rating, counts nothing. That
 * matches the recompute's documented behaviour — "a player with no current rating counts none of their
 * awards" — rather than silently treating unknown as a match.
 */
fun awardCountsInBand(
    awardBand: String?,
    currentLevel: String?,
): Boolean = awardBand != null && currentLevel != null && awardBand == currentLevel
