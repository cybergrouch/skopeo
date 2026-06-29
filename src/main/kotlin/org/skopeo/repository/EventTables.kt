// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

private const val EVENT_NAME_MAX = 255
private const val EVENT_CODE_MAX = 6

/** Events/meets that contain matches (issue #138): a name, a date range, and a shareable code. */
internal object EventsTable : UUIDTable(name = "events") {
    val publicCode = varchar(name = "public_code", length = EVENT_CODE_MAX)
    val name = varchar(name = "name", length = EVENT_NAME_MAX)
    val startDate = date(name = "start_date")
    val endDate = date(name = "end_date")
    val createdBy = reference(name = "created_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val isActive = bool(name = "is_active").default(defaultValue = true)
    val disabledAt = datetime(name = "disabled_at").nullable()
}

internal object EventParticipantsTable : UUIDTable(name = "event_participants") {
    val eventId = reference(name = "event_id", foreign = EventsTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference(name = "user_id", foreign = UsersTable, onDelete = ReferenceOption.CASCADE)
}
