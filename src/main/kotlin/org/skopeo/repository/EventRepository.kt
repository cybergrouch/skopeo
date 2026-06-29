// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.skopeo.model.CreateEventCommand
import org.skopeo.model.Event
import java.util.UUID

/** Persistence for events/meets (issue #138): the event row plus its participant roster. */
class EventRepository {
    fun create(command: CreateEventCommand): Event =
        transaction {
            val id =
                EventsTable.insertAndGetId {
                    it[publicCode] = generateUniqueEventCode()
                    it[name] = command.name
                    it[startDate] = command.startDate
                    it[endDate] = command.endDate
                    it[createdBy] = command.createdBy
                }.value
            command.participantIds.distinct().forEach { uid -> addParticipantRow(eventId = id, userId = uid) }
            loadEventOrThrow(id = id)
        }

    /** Active events, newest start date first. When [createdBy] is set, scoped to that creator (HOST). */
    fun list(createdBy: UUID?): List<Event> =
        transaction {
            EventsTable
                .selectAll()
                .where {
                    if (createdBy != null) {
                        EventsTable.isActive and (EventsTable.createdBy eq createdBy)
                    } else {
                        EventsTable.isActive eq true
                    }
                }.orderBy(EventsTable.startDate to SortOrder.DESC)
                .map { buildEvent(row = it) }
        }

    fun findById(id: UUID): Event? = transaction { loadEvent(id = id) }

    /** Resolve an active event by its shareable public code (#138); null if absent or disabled. */
    fun findByPublicCode(code: String): Event? =
        transaction {
            EventsTable
                .selectAll()
                .where { (EventsTable.publicCode eq code) and EventsTable.isActive }
                .singleOrNull()
                ?.let { buildEvent(row = it) }
        }

    /** Add a participant (idempotent); returns the updated event, or null if the event is absent. */
    fun addParticipant(
        eventId: UUID,
        userId: UUID,
    ): Event? =
        transaction {
            if (loadEvent(id = eventId) == null) {
                null
            } else {
                val present =
                    EventParticipantsTable
                        .selectAll()
                        .where { (EventParticipantsTable.eventId eq eventId) and (EventParticipantsTable.userId eq userId) }
                        .any()
                if (!present) addParticipantRow(eventId = eventId, userId = userId)
                loadEvent(id = eventId)
            }
        }

    /** Remove a participant (a no-op if absent from the roster); returns the updated event, or null if the event is absent. */
    fun removeParticipant(
        eventId: UUID,
        userId: UUID,
    ): Event? =
        transaction {
            if (loadEvent(id = eventId) == null) {
                null
            } else {
                EventParticipantsTable.deleteWhere {
                    (EventParticipantsTable.eventId eq eventId) and (EventParticipantsTable.userId eq userId)
                }
                loadEvent(id = eventId)
            }
        }

    private fun addParticipantRow(
        eventId: UUID,
        userId: UUID,
    ) {
        EventParticipantsTable.insert {
            it[EventParticipantsTable.eventId] = eventId
            it[EventParticipantsTable.userId] = userId
        }
    }

    private fun loadEvent(id: UUID): Event? =
        EventsTable.selectAll().where { EventsTable.id eq id }.singleOrNull()?.let { buildEvent(row = it) }

    private fun loadEventOrThrow(id: UUID): Event = buildEvent(row = EventsTable.selectAll().where { EventsTable.id eq id }.single())

    private fun buildEvent(row: ResultRow): Event {
        val id = row[EventsTable.id].value
        return Event(
            id = id,
            publicCode = row[EventsTable.publicCode],
            name = row[EventsTable.name],
            startDate = row[EventsTable.startDate],
            endDate = row[EventsTable.endDate],
            participantIds = participantIdsOf(eventId = id),
            isActive = row[EventsTable.isActive],
            createdBy = row[EventsTable.createdBy]?.value,
        )
    }

    private fun participantIdsOf(eventId: UUID): List<UUID> =
        EventParticipantsTable
            .selectAll()
            .where { EventParticipantsTable.eventId eq eventId }
            .map { it[EventParticipantsTable.userId].value }

    private fun generateUniqueEventCode(): String =
        PublicCode.generate { code -> EventsTable.selectAll().where { EventsTable.publicCode eq code }.any() }
}
