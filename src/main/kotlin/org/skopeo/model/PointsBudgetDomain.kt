// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.model

import java.util.UUID

/**
 * The global master points policy for one event type (#403 Phase B, §2.1). Per type the policy
 * bounds the per-match reward ([minPoints]..[maxPoints]) events may designate, and how long an
 * awarded point may stay valid ([maxValidityDays]). All values are whole integers (decision #6).
 */
data class PointsPolicy(
    val eventType: EventType,
    val minPoints: Int,
    val maxPoints: Int,
    val maxValidityDays: Int,
)

/**
 * A club's budget allocation for one event type (#403 Phase B, §2.2): the total number of ranking
 * points the club may designate for that type. Whole integers (decision #6).
 */
data class ClubPointBudget(
    val clubId: UUID,
    val eventType: EventType,
    val budgetedPoints: Int,
)

/**
 * The per club × event-type accounting view (#403, §2.2): [budgeted] is the club's real allocation,
 * [allocated] the points currently in use, and [free] = [budgeted] − [allocated] the remaining
 * allocable amount. As of Phase C, [allocated] includes the EMERGENT reservations — the sum of the
 * club's active, non-finalized fixtures' designations × team size — so it now reflects real
 * utilization (active awards are added in Phase D; still 0 until then). The field keeps the name
 * `allocated` to avoid churn, but it means "reserved + active awarded".
 */
data class ClubBudgetView(
    val clubId: UUID,
    val eventType: EventType,
    val budgeted: Int,
    val allocated: Int,
    val free: Int,
)

/**
 * One event's points figures in a club owner's points summary (#403 Phase E): the same per-event
 * points that are public ([designated] planned, [awarded] finalized), plus [finalized] so the owner
 * view can label which figure is live. This is the per-event breakdown that accompanies (but is
 * distinct from) the club's per-type utilization.
 */
data class ClubEventPointsView(
    val eventPublicCode: String,
    val name: String,
    val eventType: EventType,
    val finalized: Boolean,
    val designated: Int,
    val awarded: Int,
)

/**
 * The CLUB_OWNER / points-manager points summary for a club (#403 Phase E), served only to that
 * club's owners (or ADMINISTRATOR/POINTS_MANAGER): the club's per-type utilization ([utilization],
 * the same Budgeted/Allocated/Free rows the Points Management tab shows) plus a per-event breakdown
 * ([events]). Utilization is deliberately kept OFF the anonymous public club response.
 */
data class ClubPointsSummaryView(
    val clubId: UUID,
    val utilization: List<ClubBudgetView>,
    val events: List<ClubEventPointsView>,
)
