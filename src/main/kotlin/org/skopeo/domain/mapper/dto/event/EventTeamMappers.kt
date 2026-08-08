// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.mapper.dto.event

import org.skopeo.common.dto.event.EventTeamMemberResponse
import org.skopeo.common.dto.event.EventTeamResponse
import org.skopeo.domain.model.EventTeamMemberRef
import org.skopeo.domain.model.EventTeamView

/** Domain→DTO mapper (#720): the durable event team plus its resolved, slot-ordered members. */
fun EventTeamView.toResponse(): EventTeamResponse =
    EventTeamResponse(
        id = team.id.toString(),
        eventId = team.eventId.toString(),
        name = team.name,
        members = members.map { it.toResponse() },
    )

internal fun EventTeamMemberRef.toResponse(): EventTeamMemberResponse =
    EventTeamMemberResponse(
        userId = userId.toString(),
        position = position,
        displayName = displayName,
        publicCode = publicCode,
        isPlaceholder = placeholder,
        isDeleted = deleted,
    )
