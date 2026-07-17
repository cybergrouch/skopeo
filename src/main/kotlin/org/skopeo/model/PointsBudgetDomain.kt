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
