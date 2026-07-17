// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.dto.points

import kotlinx.serialization.Serializable
import org.skopeo.model.ClubBudgetView
import org.skopeo.model.PointsPolicy

/** One global per-type policy in a response (#403 Phase B). */
@Serializable
data class PointsPolicyResponse(
    val eventType: String,
    val minPoints: Int,
    val maxPoints: Int,
    val maxValidityDays: Int,
)

/** Body for `PUT /api/v1/points/policies/{eventType}` — set a type's global policy (#403 Phase B). */
@Serializable
data class SetPointsPolicyRequest(
    val minPoints: Int,
    val maxPoints: Int,
    val maxValidityDays: Int,
)

/** One club × event-type accounting row (#403 Phase B): budgeted (real) / allocated (0 for now) / free. */
@Serializable
data class ClubBudgetResponse(
    val clubId: String,
    val eventType: String,
    val budgeted: Int,
    val allocated: Int,
    val free: Int,
)

/** Body for `PUT /api/v1/clubs/{clubId}/point-budgets/{eventType}` — set a club's per-type budget. */
@Serializable
data class SetClubBudgetRequest(
    val budgetedPoints: Int,
)

fun PointsPolicy.toResponse(): PointsPolicyResponse =
    PointsPolicyResponse(
        eventType = eventType.name,
        minPoints = minPoints,
        maxPoints = maxPoints,
        maxValidityDays = maxValidityDays,
    )

fun ClubBudgetView.toResponse(): ClubBudgetResponse =
    ClubBudgetResponse(
        clubId = clubId.toString(),
        eventType = eventType.name,
        budgeted = budgeted,
        allocated = allocated,
        free = free,
    )
