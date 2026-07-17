// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.model.ClubPointBudget
import org.skopeo.model.EventType
import org.skopeo.model.PointsPolicy
import java.time.LocalDateTime
import java.util.UUID

/**
 * Persistence for the points-budget foundation (#403 Phase B): the global per-type policies and each
 * club's per-type budget allocations. Both are upsert-on-primary-key stores mirroring the
 * app_settings pattern; the event type is persisted as its enum name.
 */
class PointsBudgetRepository {
    /** All global policies (one per event type), ordered by event type for a stable read. */
    fun listPolicies(): List<PointsPolicy> =
        transaction {
            PointsPoliciesTable
                .selectAll()
                .orderBy(PointsPoliciesTable.eventType to SortOrder.ASC)
                .map { it.toPolicy() }
        }

    /** The global policy for [eventType], or null if none is seeded. */
    fun findPolicy(eventType: EventType): PointsPolicy? =
        transaction {
            PointsPoliciesTable
                .selectAll()
                .where { PointsPoliciesTable.eventType eq eventType.name }
                .singleOrNull()
                ?.toPolicy()
        }

    /** Insert-or-update the global policy for its event type; returns the stored row. */
    fun upsertPolicy(policy: PointsPolicy): PointsPolicy =
        transaction {
            val exists =
                PointsPoliciesTable.selectAll().where { PointsPoliciesTable.eventType eq policy.eventType.name }.any()
            if (exists) {
                PointsPoliciesTable.update(where = { PointsPoliciesTable.eventType eq policy.eventType.name }) {
                    it[minPoints] = policy.minPoints
                    it[maxPoints] = policy.maxPoints
                    it[maxValidityDays] = policy.maxValidityDays
                }
            } else {
                PointsPoliciesTable.insert {
                    it[eventType] = policy.eventType.name
                    it[minPoints] = policy.minPoints
                    it[maxPoints] = policy.maxPoints
                    it[maxValidityDays] = policy.maxValidityDays
                }
            }
            PointsPoliciesTable.selectAll().where { PointsPoliciesTable.eventType eq policy.eventType.name }.single().toPolicy()
        }

    /** Every club's per-type budgets (all clubs × types that have a row). */
    fun listBudgets(): List<ClubPointBudget> = transaction { ClubPointBudgetsTable.selectAll().map { it.toBudget() } }

    /** One club's per-type budgets. */
    fun listBudgetsForClub(clubId: UUID): List<ClubPointBudget> =
        transaction {
            ClubPointBudgetsTable.selectAll().where { ClubPointBudgetsTable.clubId eq clubId }.map { it.toBudget() }
        }

    /** Insert-or-update a club's budget for one event type; returns the stored row. */
    fun upsertBudget(
        clubId: UUID,
        eventType: EventType,
        budgetedPoints: Int,
        updatedBy: UUID,
    ): ClubPointBudget =
        transaction {
            val now = LocalDateTime.now()
            val match: SqlExpressionBuilder.() -> Op<Boolean> = {
                (ClubPointBudgetsTable.clubId eq clubId) and (ClubPointBudgetsTable.eventType eq eventType.name)
            }
            val exists = ClubPointBudgetsTable.selectAll().where(predicate = match).any()
            if (exists) {
                ClubPointBudgetsTable.update(where = match) {
                    it[ClubPointBudgetsTable.budgetedPoints] = budgetedPoints
                    it[ClubPointBudgetsTable.updatedBy] = updatedBy
                    it[updatedAt] = now
                }
            } else {
                ClubPointBudgetsTable.insert {
                    it[ClubPointBudgetsTable.clubId] = clubId
                    it[ClubPointBudgetsTable.eventType] = eventType.name
                    it[ClubPointBudgetsTable.budgetedPoints] = budgetedPoints
                    it[ClubPointBudgetsTable.updatedBy] = updatedBy
                    it[updatedAt] = now
                }
            }
            ClubPointBudgetsTable.selectAll().where(predicate = match).single().toBudget()
        }

    private fun ResultRow.toPolicy(): PointsPolicy =
        PointsPolicy(
            eventType = EventType.valueOf(value = this[PointsPoliciesTable.eventType]),
            minPoints = this[PointsPoliciesTable.minPoints],
            maxPoints = this[PointsPoliciesTable.maxPoints],
            maxValidityDays = this[PointsPoliciesTable.maxValidityDays],
        )

    private fun ResultRow.toBudget(): ClubPointBudget =
        ClubPointBudget(
            clubId = this[ClubPointBudgetsTable.clubId].value,
            eventType = EventType.valueOf(value = this[ClubPointBudgetsTable.eventType]),
            budgetedPoints = this[ClubPointBudgetsTable.budgetedPoints],
        )
}
