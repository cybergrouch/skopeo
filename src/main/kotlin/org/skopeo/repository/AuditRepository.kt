// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.skopeo.model.AuditAction
import org.skopeo.model.AuditEntityType
import org.skopeo.model.AuditEntry
import org.skopeo.model.AuditWrite
import java.time.LocalDateTime
import java.util.UUID

private val DETAILS_SERIALIZER = MapSerializer(keySerializer = String.serializer(), valueSerializer = String.serializer().nullable)

/** Append-only persistence for the audit log (issue #100): record an action, read it back newest-first. */
class AuditRepository {
    fun record(write: AuditWrite) {
        transaction {
            AuditLogTable.insert {
                it[occurredAt] = LocalDateTime.now()
                it[actorUserId] = write.actorUserId
                it[action] = write.action.name
                it[entityType] = write.entityType.name
                it[entityId] = write.entityId
                it[summary] = write.summary
                it[details] =
                    if (write.details.isEmpty()) {
                        null
                    } else {
                        Json.encodeToString(serializer = DETAILS_SERIALIZER, value = write.details)
                    }
            }
        }
    }

    /** Set/replace the free-text admin note on an entry; returns false if no such entry. */
    fun updateComment(
        id: UUID,
        comment: String?,
    ): Boolean =
        transaction {
            AuditLogTable.update(where = { AuditLogTable.id eq id }) { it[AuditLogTable.comment] = comment } > 0
        }

    /** One page of entries (newest first) plus the total, optionally scoped to a single [action]. */
    fun list(
        action: AuditAction?,
        limit: Int,
        offset: Int,
    ): Pair<List<AuditEntry>, Long> =
        transaction {
            fun query() =
                if (action == null) {
                    AuditLogTable.selectAll()
                } else {
                    AuditLogTable.selectAll().where { AuditLogTable.action eq action.name }
                }
            val total = query().count()
            val items =
                query()
                    .orderBy(AuditLogTable.occurredAt to SortOrder.DESC)
                    .limit(n = limit, offset = offset.toLong())
                    .map { it.toAuditEntry() }
            items to total
        }
}

internal fun ResultRow.toAuditEntry(): AuditEntry =
    AuditEntry(
        id = this[AuditLogTable.id].value,
        occurredAt = this[AuditLogTable.occurredAt],
        actorUserId = this[AuditLogTable.actorUserId]?.value,
        action = AuditAction.valueOf(value = this[AuditLogTable.action]),
        entityType = AuditEntityType.valueOf(value = this[AuditLogTable.entityType]),
        entityId = this[AuditLogTable.entityId],
        summary = this[AuditLogTable.summary],
        details =
            this[AuditLogTable.details]
                ?.let { Json.decodeFromString(deserializer = DETAILS_SERIALIZER, string = it) }
                .orEmpty(),
        comment = this[AuditLogTable.comment],
    )
