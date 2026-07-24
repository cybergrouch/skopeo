// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

private const val EVENT_NAME_MAX = 255
private const val EVENT_CODE_MAX = 6
private const val EVENT_TYPE_MAX = 16
private const val PARTICIPANT_STATUS_MAX = 20

/** Events/meets that contain matches (issue #138): a name, a date range, and a shareable code. */
internal object EventsTable : UUIDTable(name = "events") {
    val publicCode = varchar(name = "public_code", length = EVENT_CODE_MAX)
    val name = varchar(name = "name", length = EVENT_NAME_MAX)
    val startDate = date(name = "start_date")
    val endDate = date(name = "end_date")
    val createdBy = reference(name = "created_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val isActive = bool(name = "is_active").default(defaultValue = true)
    val disabledAt = datetime(name = "disabled_at").nullable()

    // The club this event belongs to (#313), or null for a clubless event. SET NULL on club delete.
    val clubId = reference(name = "club_id", foreign = ClubsTable, onDelete = ReferenceOption.SET_NULL).nullable()

    // The circuit a TOURNAMENT event belongs to (#525); SET NULL on circuit delete.
    val circuitId = reference(name = "circuit_id", foreign = CircuitsTable, onDelete = ReferenceOption.SET_NULL).nullable()

    // Admin override for calculation processing order (#335); null = order by end_date.
    val calcPriority = double(name = "calc_priority").nullable()

    // The event's class (#403): OPEN_PLAY | LEAGUE | TOURNAMENT; backfilled to OPEN_PLAY.
    val type = varchar(name = "type", length = EVENT_TYPE_MAX).default(defaultValue = "OPEN_PLAY")

    // Finalize state (#403): an event is finalized iff finalized_at is non-null, stamped with the actor.
    val finalizedAt = datetime(name = "finalized_at").nullable()
    val finalizedBy = reference(name = "finalized_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()

    // Points config (#403 Phase C): the per-match reward window and the point validity window. Required
    // for a club event of any type (enforced in the service; OPEN_PLAY unified), null for clubless events.
    val minPointsPerMatch = integer(name = "min_points_per_match").nullable()
    val maxPointsPerMatch = integer(name = "max_points_per_match").nullable()
    val pointValidityStart = date(name = "point_validity_start").nullable()
    val pointValidityEnd = date(name = "point_validity_end").nullable()
}

internal object EventParticipantsTable : UUIDTable(name = "event_participants") {
    val eventId = reference(name = "event_id", foreign = EventsTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference(name = "user_id", foreign = UsersTable, onDelete = ReferenceOption.CASCADE)

    // Self-signup + host approval (#201): APPROVED roster members vs PENDING/HOLD requests.
    val status = varchar(name = "status", length = PARTICIPANT_STATUS_MAX).default(defaultValue = "APPROVED")
    val requestedAt = datetime(name = "requested_at").nullable()
    val approvedBy = reference(name = "approved_by", foreign = UsersTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val approvedAt = datetime(name = "approved_at").nullable()
}
