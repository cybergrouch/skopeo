// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.domain.model.CreateEventTeamCommand
import org.skopeo.domain.model.UpdateEventTeamCommand
import org.skopeo.repository.persistence.EventTeamAggregateEntity
import org.skopeo.repository.persistence.EventTeamEntity
import org.skopeo.repository.persistence.EventTeamMemberEntity
import java.util.UUID

/** Persistence for durable, event-scoped teams (#720): the `event_teams` row plus its ordered members. */
class EventTeamRepository {
    fun create(command: CreateEventTeamCommand): EventTeamAggregateEntity =
        transaction {
            val teamId =
                EventTeamsTable.insertAndGetId {
                    it[eventId] = command.eventId
                    it[name] = command.name
                }.value
            insertMembers(teamId = teamId, eventId = command.eventId, memberUserIds = command.memberUserIds)
            loadTeamOrThrow(id = teamId)
        }

    /** All teams for an event (#720), oldest first, each with its ordered members. */
    fun listByEvent(eventId: UUID): List<EventTeamAggregateEntity> =
        transaction {
            EventTeamsTable
                .selectAll()
                .where { EventTeamsTable.eventId eq eventId }
                .orderBy(EventTeamsTable.id to SortOrder.ASC)
                .map { buildAggregate(row = it) }
        }

    fun findById(id: UUID): EventTeamAggregateEntity? = transaction { loadTeam(id = id) }

    /**
     * Update a team's name and replace its members (#720). Returns the updated team, or null if the
     * team doesn't exist. The members are wiped and re-inserted so slot order reflects [command].
     */
    fun update(command: UpdateEventTeamCommand): EventTeamAggregateEntity? =
        transaction {
            val existing = loadTeam(id = command.teamId) ?: return@transaction null
            EventTeamsTable.update(where = { EventTeamsTable.id eq command.teamId }) { it[name] = command.name }
            EventTeamMembersTable.deleteWhere { EventTeamMembersTable.teamId eq command.teamId }
            insertMembers(teamId = command.teamId, eventId = existing.team.eventId, memberUserIds = command.memberUserIds)
            loadTeam(id = command.teamId)
        }

    /** Dissolve a team (#720): hard-delete it (its members cascade). Returns false if the team was absent. */
    fun delete(id: UUID): Boolean =
        transaction {
            EventTeamMembersTable.deleteWhere { teamId eq id }
            EventTeamsTable.deleteWhere { EventTeamsTable.id eq id } > 0
        }

    /**
     * The user ids already assigned to some team in [eventId], optionally excluding one team (#720) —
     * the basis for the exclusive-membership check (a participant is in at most one team per event).
     */
    fun memberUserIdsInEvent(
        eventId: UUID,
        excludeTeamId: UUID? = null,
    ): Set<UUID> =
        transaction {
            EventTeamMembersTable
                .selectAll()
                .where {
                    if (excludeTeamId == null) {
                        EventTeamMembersTable.eventId eq eventId
                    } else {
                        (EventTeamMembersTable.eventId eq eventId) and (EventTeamMembersTable.teamId neq excludeTeamId)
                    }
                }.map { it[EventTeamMembersTable.userId].value }
                .toSet()
        }

    private fun insertMembers(
        teamId: UUID,
        eventId: UUID,
        memberUserIds: List<UUID>,
    ) {
        memberUserIds.forEachIndexed { index, uid ->
            EventTeamMembersTable.insert {
                it[EventTeamMembersTable.teamId] = teamId
                it[EventTeamMembersTable.eventId] = eventId
                it[userId] = uid
                it[position] = index + 1
            }
        }
    }

    private fun loadTeam(id: UUID): EventTeamAggregateEntity? =
        EventTeamsTable.selectAll().where { EventTeamsTable.id eq id }.singleOrNull()?.let { buildAggregate(row = it) }
}

/** Reload a team that is known to exist (e.g. just inserted) — no caller-side null branch. Runs in a transaction. */
private fun loadTeamOrThrow(id: UUID): EventTeamAggregateEntity =
    buildAggregate(row = EventTeamsTable.selectAll().where { EventTeamsTable.id eq id }.single())

/** Assemble the raw [EventTeamAggregateEntity] graph (#720): the row scalars plus its ordered members. */
private fun buildAggregate(row: ResultRow): EventTeamAggregateEntity {
    val entity = row.toEventTeamEntity()
    return EventTeamAggregateEntity(team = entity, members = membersOf(teamId = entity.id))
}

private fun ResultRow.toEventTeamEntity(): EventTeamEntity =
    EventTeamEntity(
        id = this[EventTeamsTable.id].value,
        eventId = this[EventTeamsTable.eventId].value,
        name = this[EventTeamsTable.name],
    )

private fun membersOf(teamId: UUID): List<EventTeamMemberEntity> =
    EventTeamMembersTable
        .selectAll()
        .where { EventTeamMembersTable.teamId eq teamId }
        .orderBy(EventTeamMembersTable.position to SortOrder.ASC)
        .map { EventTeamMemberEntity(userId = it[EventTeamMembersTable.userId].value, position = it[EventTeamMembersTable.position]) }
