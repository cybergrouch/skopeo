// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.model.CreateEventCommand
import org.skopeo.model.EventParticipantEntry
import org.skopeo.model.EventParticipantStatus
import org.skopeo.repository.persistence.EventAggregateEntity
import org.skopeo.repository.persistence.EventEntity
import org.skopeo.repository.persistence.MyEventEntity
import java.time.LocalDateTime
import java.util.UUID

/** Persistence for events/meets (issue #138): the event row plus its participant roster (#201). */
class EventRepository {
    fun create(command: CreateEventCommand): EventAggregateEntity =
        transaction {
            val id =
                EventsTable.insertAndGetId {
                    it[publicCode] = generateUniqueEventCode()
                    it[name] = command.name
                    it[startDate] = command.startDate
                    it[endDate] = command.endDate
                    it[createdBy] = command.createdBy
                    it[clubId] = command.clubId
                    it[circuitId] = command.circuitId
                    it[type] = command.type.name
                    it[awardRankingPoints] = command.awardRankingPoints
                }.value
            // Host-listed participants join APPROVED outright, attributed to the creator.
            command.participantIds.distinct().forEach { uid ->
                insertParticipant(eventId = id, userId = uid, status = EventParticipantStatus.APPROVED, approvedBy = command.createdBy)
            }
            loadEventOrThrow(id = id)
        }

    /** Active events, newest start date first. When [createdBy] is set, scoped to that creator (HOST). */
    fun list(createdBy: UUID?): List<EventAggregateEntity> =
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
                .map { buildEventAggregate(row = it) }
        }

    fun findById(id: UUID): EventAggregateEntity? = transaction { loadEvent(id = id) }

    /**
     * The event with [id], which the caller knows exists — e.g. resolving a match's `event_id`, an
     * FK (`ON DELETE SET NULL`) so a non-null value always points to a live row (#358). Throws if
     * absent, rather than silently swallowing an impossible null.
     */
    fun getById(id: UUID): EventAggregateEntity = transaction { loadEventOrThrow(id = id) }

    /** Active events belonging to [clubId] (#325) — the club-delete cascade soft-deletes each of these. */
    fun listByClub(clubId: UUID): List<EventAggregateEntity> =
        transaction {
            EventsTable
                .selectAll()
                .where { EventsTable.isActive and (EventsTable.clubId eq clubId) }
                .map { buildEventAggregate(row = it) }
        }

    /** Rename an event (#269): update its name and return the event, or null if it doesn't exist. */
    fun rename(
        id: UUID,
        name: String,
    ): EventAggregateEntity? =
        transaction {
            if (loadEvent(id = id) == null) {
                null
            } else {
                EventsTable.update(where = { EventsTable.id eq id }) { it[EventsTable.name] = name }
                loadEvent(id = id)
            }
        }

    /**
     * Set (or clear, when [clubId] is null) an event's club (#319). The caller (EventService.setClub)
     * has already confirmed the event exists (for authz), so this just writes the club FK; an update
     * against a missing id is a harmless no-op.
     */
    fun updateClub(
        id: UUID,
        clubId: UUID?,
    ): Unit =
        transaction {
            EventsTable.update(where = { EventsTable.id eq id }) { it[EventsTable.clubId] = clubId }
            Unit
        }

    /**
     * Set an event's calculation-order priority (#335). The caller (EventService) has already
     * confirmed the event exists, so an update against a missing id is a harmless no-op.
     */
    fun setCalcPriority(
        id: UUID,
        priority: Double,
    ): Unit =
        transaction {
            EventsTable.update(where = { EventsTable.id eq id }) { it[calcPriority] = priority }
            Unit
        }

    /**
     * Finalize an event (#403): stamp finalized_at/finalized_by. The caller (EventService.finalize)
     * has already confirmed the event exists and is not yet finalized, so an update against a missing
     * id is a harmless no-op.
     */
    fun finalize(
        id: UUID,
        finalizedAt: LocalDateTime,
        finalizedBy: UUID,
    ): Unit =
        transaction {
            EventsTable.update(where = { EventsTable.id eq id }) {
                it[EventsTable.finalizedAt] = finalizedAt
                it[EventsTable.finalizedBy] = finalizedBy
            }
            Unit
        }

    /**
     * Un-finalize an event (#477): null out finalized_at/finalized_by, reversing [finalize]. The caller
     * (EventService.unfinalize) has already confirmed the event exists and is finalized, so an update
     * against a missing id is a harmless no-op.
     */
    fun unfinalize(id: UUID): Unit =
        transaction {
            EventsTable.update(where = { EventsTable.id eq id }) {
                it[finalizedAt] = null
                it[finalizedBy] = null
            }
            Unit
        }

    /** Soft-delete/restore an event (#243): flip is_active and stamp/clear disabled_at. Returns false if absent. */
    fun setActive(
        id: UUID,
        active: Boolean,
        disabledAt: LocalDateTime?,
    ): Boolean =
        transaction {
            EventsTable.update(where = { EventsTable.id eq id }) {
                it[isActive] = active
                it[EventsTable.disabledAt] = disabledAt
            } > 0
        }

    /**
     * Resolve an event by its shareable public code (#138); null if absent. Disabled (soft-deleted)
     * events still resolve (#325): their links stay honored for traceability — the public page flags
     * them as deleted. Ratings depend on historical matches, and deletion never touches ratings.
     */
    fun findByPublicCode(code: String): EventAggregateEntity? =
        transaction {
            EventsTable
                .selectAll()
                .where { EventsTable.publicCode eq code }
                .singleOrNull()
                ?.let { buildEventAggregate(row = it) }
        }

    /**
     * The shareable public code for each of [ids] that exists, keyed by event id (#448) — a light
     * batch lookup for linking points-audit rows to `/events/:code` without loading full events.
     */
    fun publicCodesByIds(ids: List<UUID>): Map<UUID, String> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            transaction {
                EventsTable
                    .select(columns = listOf(EventsTable.id, EventsTable.publicCode))
                    .where { EventsTable.id inList ids }
                    .associate { it[EventsTable.id].value to it[EventsTable.publicCode] }
            }
        }

    /**
     * Host-add a participant outright as APPROVED (#201, idempotent). If the user already has a row
     * (e.g. a PENDING self-signup), it's promoted to APPROVED. Returns the event, or null if absent.
     */
    fun addParticipant(
        eventId: UUID,
        userId: UUID,
        approvedBy: UUID,
    ): EventAggregateEntity? =
        transaction {
            if (loadEvent(id = eventId) == null) {
                null
            } else {
                if (hasParticipant(eventId = eventId, userId = userId)) {
                    setStatus(eventId = eventId, userId = userId, status = EventParticipantStatus.APPROVED, approvedBy = approvedBy)
                } else {
                    insertParticipant(eventId = eventId, userId = userId, status = EventParticipantStatus.APPROVED, approvedBy = approvedBy)
                }
                loadEvent(id = eventId)
            }
        }

    /**
     * Self-signup (#201): a player adds themselves as PENDING. Idempotent — if they already have a
     * row in any status it's left untouched. Returns the event, or null if absent.
     */
    fun selfSignup(
        eventId: UUID,
        userId: UUID,
    ): EventAggregateEntity? =
        transaction {
            if (loadEvent(id = eventId) == null) {
                null
            } else {
                if (!hasParticipant(eventId = eventId, userId = userId)) {
                    insertParticipant(eventId = eventId, userId = userId, status = EventParticipantStatus.PENDING, approvedBy = null)
                }
                loadEvent(id = eventId)
            }
        }

    /**
     * Set a participant's status (#201) — APPROVE or HOLD a request. [approvedBy]/approved_at are
     * stamped when APPROVED, cleared otherwise. Returns the event, or null if the event is absent.
     */
    fun setParticipantStatus(
        eventId: UUID,
        userId: UUID,
        status: EventParticipantStatus,
        approvedBy: UUID?,
    ): EventAggregateEntity? =
        transaction {
            if (loadEvent(id = eventId) == null) {
                null
            } else {
                setStatus(eventId = eventId, userId = userId, status = status, approvedBy = approvedBy)
                loadEvent(id = eventId)
            }
        }

    /** Remove a participant (a no-op if absent); returns the updated event, or null if the event is absent. */
    fun removeParticipant(
        eventId: UUID,
        userId: UUID,
    ): EventAggregateEntity? =
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

    /** A player's own events with their standing in each (#202), active only, newest end date first. */
    fun findForParticipant(userId: UUID): List<MyEventEntity> =
        transaction {
            (EventParticipantsTable innerJoin EventsTable)
                .selectAll()
                .where { (EventParticipantsTable.userId eq userId) and EventsTable.isActive }
                .orderBy(EventsTable.endDate to SortOrder.DESC)
                .map {
                    MyEventEntity(
                        event = buildEventAggregate(row = it),
                        status = it[EventParticipantsTable.status],
                    )
                }
        }

    /** All participant rows for an event with their status (#201) — the roster + pending/held requests. */
    fun participantsOf(eventId: UUID): List<EventParticipantEntry> =
        transaction {
            EventParticipantsTable
                .selectAll()
                .where { EventParticipantsTable.eventId eq eventId }
                .map {
                    EventParticipantEntry(
                        userId = it[EventParticipantsTable.userId].value,
                        status = EventParticipantStatus.valueOf(value = it[EventParticipantsTable.status]),
                    )
                }
        }

    private fun hasParticipant(
        eventId: UUID,
        userId: UUID,
    ): Boolean =
        EventParticipantsTable
            .selectAll()
            .where { (EventParticipantsTable.eventId eq eventId) and (EventParticipantsTable.userId eq userId) }
            .any()

    private fun setStatus(
        eventId: UUID,
        userId: UUID,
        status: EventParticipantStatus,
        approvedBy: UUID?,
    ) {
        val approved = status == EventParticipantStatus.APPROVED
        EventParticipantsTable.update(
            where = { (EventParticipantsTable.eventId eq eventId) and (EventParticipantsTable.userId eq userId) },
        ) {
            it[EventParticipantsTable.status] = status.name
            it[EventParticipantsTable.approvedBy] = if (approved) approvedBy else null
            it[EventParticipantsTable.approvedAt] = if (approved) LocalDateTime.now() else null
        }
    }

    private fun insertParticipant(
        eventId: UUID,
        userId: UUID,
        status: EventParticipantStatus,
        approvedBy: UUID?,
    ) {
        val approved = status == EventParticipantStatus.APPROVED
        EventParticipantsTable.insert {
            it[EventParticipantsTable.eventId] = eventId
            it[EventParticipantsTable.userId] = userId
            it[EventParticipantsTable.status] = status.name
            it[requestedAt] = if (status == EventParticipantStatus.PENDING) LocalDateTime.now() else null
            it[EventParticipantsTable.approvedBy] = if (approved) approvedBy else null
            it[approvedAt] = if (approved) LocalDateTime.now() else null
        }
    }

    private fun loadEvent(id: UUID): EventAggregateEntity? =
        EventsTable.selectAll().where { EventsTable.id eq id }.singleOrNull()?.let { buildEventAggregate(row = it) }

    private fun loadEventOrThrow(id: UUID): EventAggregateEntity =
        buildEventAggregate(row = EventsTable.selectAll().where { EventsTable.id eq id }.single())

    /** Assemble the raw [EventAggregateEntity] graph (#633): the `events` row scalars plus its APPROVED roster ids. */
    private fun buildEventAggregate(row: ResultRow): EventAggregateEntity {
        val entity = row.toEventEntity()
        return EventAggregateEntity(event = entity, participantIds = approvedParticipantIdsOf(eventId = entity.id))
    }

    /** Read only the raw `events`-row scalars into the persistence entity (#633); no child rows. */
    private fun ResultRow.toEventEntity(): EventEntity =
        EventEntity(
            id = this[EventsTable.id].value,
            publicCode = this[EventsTable.publicCode],
            name = this[EventsTable.name],
            startDate = this[EventsTable.startDate],
            endDate = this[EventsTable.endDate],
            isActive = this[EventsTable.isActive],
            createdBy = this[EventsTable.createdBy]?.value,
            clubId = this[EventsTable.clubId]?.value,
            circuitId = this[EventsTable.circuitId]?.value,
            calcPriority = this[EventsTable.calcPriority],
            type = this[EventsTable.type],
            finalizedAt = this[EventsTable.finalizedAt],
            finalizedBy = this[EventsTable.finalizedBy]?.value,
            awardRankingPoints = this[EventsTable.awardRankingPoints],
        )

    /** Only APPROVED participants count as the roster (eligible for fixtures/seeding, #201). */
    private fun approvedParticipantIdsOf(eventId: UUID): List<UUID> =
        EventParticipantsTable
            .selectAll()
            .where {
                (EventParticipantsTable.eventId eq eventId) and
                    (EventParticipantsTable.status eq EventParticipantStatus.APPROVED.name)
            }.map { it[EventParticipantsTable.userId].value }

    private fun generateUniqueEventCode(): String =
        PublicCode.generate { code -> EventsTable.selectAll().where { EventsTable.publicCode eq code }.any() }
}
