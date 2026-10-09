// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.skopeo.common.security.Capability
import org.skopeo.repository.persistence.StaleAccountEntity
import java.time.LocalDateTime
import java.util.UUID

/**
 * The stale-account rule (#1122), stated once in SQL and read by both the sweep and the pending list's
 * countdown, so the two cannot disagree about who is going to be removed.
 *
 * An account is **eligible** when it is an active self-sign-up nobody has done anything with:
 * - active, with **no rating row** — the pending-assessment population;
 * - **not a placeholder** — hosts make those on purpose;
 * - **no active capability besides PLAYER** — staff are never swept;
 * - **no match history** (`team_users`) and **no event history** (`event_participants`);
 * - **no account merged into it** — a merge can bring rated matches onto an unrated account (#1121), so
 *   "unrated" alone does not mean "untouched";
 * - a **known** `created_at`. A row without one is never eligible: the rule goes by sign-up time, and an
 *   unknown time must not read as "old enough".
 *
 * It is **stale** once eligible and created before the cutoff. Eligibility deliberately ignores age, so
 * the pending list can show a removal date for an account that is not old enough yet.
 */
class StaleAccountRepository {
    /** Every stale account — eligible and created before [createdBefore] — oldest first. */
    fun listStale(createdBefore: LocalDateTime): List<StaleAccountEntity> =
        transaction {
            UsersTable
                .select(columns = listOf(UsersTable.id, UsersTable.publicCode, UsersTable.createdAt))
                .where { eligible() and (UsersTable.createdAt less createdBefore) }
                .orderBy(UsersTable.createdAt to SortOrder.ASC, UsersTable.id to SortOrder.ASC)
                .map { it.toEntity() }
        }

    /** Which of [userIds] are eligible, whatever their age — the pending list's countdown candidates. */
    fun eligibleAmong(userIds: Collection<UUID>): List<StaleAccountEntity> =
        if (userIds.isEmpty()) {
            emptyList()
        } else {
            transaction {
                UsersTable
                    .select(columns = listOf(UsersTable.id, UsersTable.publicCode, UsersTable.createdAt))
                    .where { eligible() and (UsersTable.id inList userIds) }
                    .map { it.toEntity() }
            }
        }

    private fun eligible(): Op<Boolean> =
        (UsersTable.isActive eq true) and
            (UsersTable.placeholder eq false) and
            UsersTable.createdAt.isNotNull() and
            (UsersTable.id notInSubQuery UserRatingsTable.select(columns = listOf(element = UserRatingsTable.userId))) and
            (UsersTable.id notInSubQuery staffUserIds()) and
            (UsersTable.id notInSubQuery TeamUsersTable.select(columns = listOf(element = TeamUsersTable.userId))) and
            (UsersTable.id notInSubQuery EventParticipantsTable.select(columns = listOf(element = EventParticipantsTable.userId))) and
            (UsersTable.id notInSubQuery mergeTargets())

    private fun staffUserIds() =
        UserCapabilitiesTable
            .select(columns = listOf(element = UserCapabilitiesTable.userId))
            .where { (UserCapabilitiesTable.isActive eq true) and (UserCapabilitiesTable.capability neq Capability.PLAYER.name) }

    // Accounts something was merged INTO: a retired duplicate, or a claimed placeholder, points here.
    private fun mergeTargets() =
        UsersTable
            .select(columns = listOf(element = UsersTable.canonicalUserId))
            .where { UsersTable.canonicalUserId.isNotNull() }

    private fun ResultRow.toEntity() =
        StaleAccountEntity(
            userId = this[UsersTable.id].value,
            publicCode = this[UsersTable.publicCode],
            // Non-null by the `isNotNull` in the eligibility clause.
            createdAt = checkNotNull(value = this[UsersTable.createdAt]),
        )
}
