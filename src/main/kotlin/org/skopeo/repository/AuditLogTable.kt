// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import org.postgresql.util.PGobject

private const val ACTION_MAX = 64
private const val ENTITY_TYPE_MAX = 40

/**
 * Append-only provenance of domain actions (issue #100). Flyway owns the DDL; this maps what the
 * repository touches. [details] is JSON (stored as `jsonb`); the column carries the JSON text and
 * [AuditRepository] (de)serializes it.
 */
internal object AuditLogTable : UUIDTable(name = "audit_log") {
    val occurredAt = datetime(name = "occurred_at")
    val actorUserId =
        reference(name = "actor_user_id", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val action = varchar(name = "action", length = ACTION_MAX)
    val entityType = varchar(name = "entity_type", length = ENTITY_TYPE_MAX)
    val entityId = uuid(name = "entity_id").nullable()
    val summary = text(name = "summary")
    val details = jsonb(name = "details").nullable()

    // A free-text admin note (#100). The one mutable field; its edits are deliberately not audited.
    val comment = text(name = "comment").nullable()
}

/** Maps a PostgreSQL `jsonb` column to/from its JSON text, so we avoid a string↔jsonb type clash. */
internal class JsonbColumnType : ColumnType<String>() {
    override fun sqlType(): String = "jsonb"

    override fun valueToDB(value: String?): Any? =
        PGobject().apply {
            type = "jsonb"
            this.value = value
        }

    override fun valueFromDB(value: Any): String = (value as PGobject).value.orEmpty()
}

private fun Table.jsonb(name: String): Column<String> = registerColumn(name = name, type = JsonbColumnType())
